package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.shared.Cursor;
import dev.rashed.dispatch.shared.CursorPage;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.InvalidCursorException;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.TimeWindow;
import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offers from the technician's side: their open offers, and accepting one. Technicians belong to no tenant,
 * so reads are native SQL guarded by row-level security on the technician id (V7), not Hibernate's tenant
 * filter.
 */
@Service
@PreAuthorize("hasRole('TECHNICIAN')")
public class OfferService {

    // Explicitly the caller's offers; row-level security enforces the same thing again.
    private static final String OFFERS = """
            SELECT o.id, o.status, o.expires_at, o.created_at, o.tenant_id, o.work_order_id,
                   w.title, w.description, w.site_address, w.site_latitude, w.site_longitude, w.required_skills,
                   w.window_starts_at, w.window_ends_at, w.budget_minor, w.budget_currency,
                   w.technician_id AS assigned_to
            FROM offer o JOIN work_order w ON w.id = o.work_order_id
            WHERE o.technician_id = :technicianId
            """;

    private final JdbcClient jdbc;
    private final WorkOrderRepository workOrders;
    private final OfferRepository offers;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;

    OfferService(
            JdbcClient jdbc,
            WorkOrderRepository workOrders,
            OfferRepository offers,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.workOrders = workOrders;
        this.offers = offers;
        this.events = events;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactionManager);
    }

    /**
     * Open offers (not yet answered or expired), newest first.
     *
     * @param after the {@code next} cursor of the previous page, or null for the first page
     */
    public CursorPage<OfferView> myOpenOffers(int limit, @Nullable String after) {
        var technicianId = currentTechnician();
        var sql = OFFERS + "  AND o.status = 'OFFERED' AND o.expires_at > :now\n"
                + (after == null ? "" : "  AND (o.created_at, o.id) < (:afterCreatedAt, :afterId)\n")
                + "ORDER BY o.created_at DESC, o.id DESC\nLIMIT :limit";
        var params = new HashMap<String, Object>();
        params.put("technicianId", technicianId);
        params.put("now", Timestamp.from(Timestamps.now()));
        params.put("limit", limit + 1);
        if (after != null) {
            var cursor = Cursor.decode(after);
            params.put("afterCreatedAt", Timestamp.from(createdAtOf(cursor)));
            params.put("afterId", cursor.id());
        }
        var rows = Objects.requireNonNull(readOnly.execute(
                status -> jdbc.sql(sql).params(params).query(OfferService::row).list()));
        return CursorPage.of(
                        rows,
                        limit,
                        r -> new Cursor(r.createdAt().toString(), r.view().id()))
                .map(Row::view);
    }

    /**
     * Accepts the offer if the work order is still free. Exactly one of many technicians accepting at the
     * same moment gets it; the others get {@link WorkOrderAlreadyTakenException} (409). Accepting again an
     * offer you already won returns it unchanged (mobile apps retry when a response gets lost).
     */
    public OfferView accept(UUID offerId) {
        var technicianId = currentTechnician();
        // 1. As the technician: is this offer theirs, and still worth trying? (Row-level security hides
        //    other technicians' offers, so someone else's offer id is simply not found.)
        var offer = Objects.requireNonNull(readOnly.execute(status -> jdbc.sql(OFFERS + "  AND o.id = :offerId")
                        .param("technicianId", technicianId)
                        .param("offerId", offerId)
                        .query(OfferService::row)
                        .optional()))
                .orElseThrow(() -> new NotFoundException("Offer", offerId));
        if (offer.view().status() == OfferStatus.ACCEPTED) {
            return offer.view();
        }
        if (offer.assignedTo() != null) {
            throw new WorkOrderAlreadyTakenException(offer.view().workOrderId());
        }
        if (offer.view().status() != OfferStatus.OFFERED) {
            throw new OfferClosedException(offerId);
        }
        // 2. As the work order's tenant, in one transaction. The work order goes first: every technician
        //    accepting this job queues on that one row, so nobody holds an offer row while waiting for it,
        //    and the losers touch nothing else (no deadlocks). Steps 1 and 2 can be minutes apart; step 2
        //    re-checks everything that matters inside its UPDATE statements.
        return TenantContext.callAs(
                offer.tenantId(),
                () -> readWrite.execute(status -> {
                    var workOrderId = offer.view().workOrderId();
                    if (!workOrders.assignIfStillRouted(workOrderId, technicianId)) {
                        throw new WorkOrderAlreadyTakenException(workOrderId);
                    }
                    var now = Timestamps.now();
                    if (offers.accept(offerId, technicianId, now) == 0) {
                        throw new OfferClosedException(offerId); // expired in the meantime: rolls the assignment back
                    }
                    offers.expireOthers(workOrderId, offerId);
                    events.publishEvent(new WorkOrderStatusChanged(
                            workOrderId,
                            offer.tenantId(),
                            WorkOrderStatus.ROUTED, // the only status Transition.ASSIGN starts from
                            Transition.ASSIGN.to,
                            technicianId,
                            now));
                    return offer.view().withStatus(OfferStatus.ACCEPTED);
                }));
    }

    private static UUID currentTechnician() {
        return CurrentUser.current()
                .map(CurrentUser::technicianId)
                .orElseThrow(() -> new AccessDeniedException("This account has no technician profile"));
    }

    private static Instant createdAtOf(Cursor cursor) {
        try {
            return Instant.parse(cursor.sortValue());
        } catch (DateTimeParseException e) {
            throw new InvalidCursorException();
        }
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        var view = new OfferView(
                rs.getObject("id", UUID.class),
                OfferStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("work_order_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("site_address"),
                new GeoPoint(rs.getDouble("site_latitude"), rs.getDouble("site_longitude")),
                List.of((String[]) rs.getArray("required_skills").getArray()),
                new TimeWindow(
                        rs.getTimestamp("window_starts_at").toInstant(),
                        rs.getTimestamp("window_ends_at").toInstant()),
                new Money(rs.getLong("budget_minor"), rs.getString("budget_currency")));
        return new Row(
                view,
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("assigned_to", UUID.class));
    }

    private record Row(
            OfferView view,
            Instant createdAt,
            UUID tenantId,
            @Nullable UUID assignedTo) {}
}

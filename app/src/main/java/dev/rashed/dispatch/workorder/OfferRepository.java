package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OfferRepository extends JpaRepository<Offer, UUID> {

    /** @return 1 if the offer was still open (offered to this technician and not expired), else 0 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Offer o set o.status = OfferStatus.ACCEPTED, o.respondedAt = :now
            where o.id = :id and o.technicianId = :technicianId
              and o.status = OfferStatus.OFFERED and o.expiresAt > :now""")
    int accept(UUID id, UUID technicianId, Instant now);

    /** The job is taken: every other open offer for it ends. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Offer o set o.status = OfferStatus.EXPIRED
            where o.workOrderId = :workOrderId and o.id <> :acceptedId and o.status = OfferStatus.OFFERED""")
    int expireOthers(UUID workOrderId, UUID acceptedId);
}

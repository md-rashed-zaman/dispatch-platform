package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.shared.Cursor;
import dev.rashed.dispatch.shared.CursorPage;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.InvalidCursorException;
import dev.rashed.dispatch.shared.Skill;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Verified technicians with all these skills within 20 km of the site, nearest first." Native SQL, because
 * the fast plan depends on its exact shape; measurements and the reasoning are in docs/perf/geo-search.md.
 */
@Service
public class TechnicianSearch {

    // How the query stays fast (docs/perf/geo-search.md):
    // - "&&" against the box around the search circle lets the GiST index skip far-away technicians. It's
    //   padded by 5% because the buffer polygon is a slightly smaller approximation of the circle; the
    //   exact distance check below decides. (Not ST_DWithin: Postgres estimates it matches a handful of
    //   rows when it matches thousands, and then never picks the nearest-first plan.)
    // - ORDER BY distance (<->) walks the GiST index nearest-first (KNN) and stops after LIMIT rows.
    // - The second distance check is the technician's own limit: they only take jobs within their radius.
    private static final String SEARCH = """
            SELECT t.id, t.full_name, t.skills, t.rating, t.home_location <-> site.point AS distance_m
            FROM technician t, (SELECT ST_Point(:longitude, :latitude, 4326)::geography AS point) site
            WHERE t.status = 'VERIFIED'
              AND t.skills @> CAST(:skills AS text[])
              AND t.home_location && ST_Buffer(site.point, :radiusMeters * 1.05)
              AND t.home_location <-> site.point <= least(:radiusMeters, t.service_radius_km * 1000)
            """;
    private static final String AFTER_CURSOR =
            "  AND (t.home_location <-> site.point, t.id) > (:afterDistance, :afterId)\n";
    private static final String ORDER_AND_LIMIT = "ORDER BY distance_m, t.id\nLIMIT :limit";

    private final JdbcClient jdbc;

    TechnicianSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param after the {@code next} cursor of the previous page, or null for the first page
     * @throws InvalidCursorException if {@code after} isn't a cursor from this search
     */
    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER', 'PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    public CursorPage<NearbyTechnician> nearby(
            GeoPoint site, int radiusKm, Collection<String> requiredSkills, int limit, @Nullable String after) {
        var query = jdbc.sql(after == null ? SEARCH + ORDER_AND_LIMIT : SEARCH + AFTER_CURSOR + ORDER_AND_LIMIT)
                .param("latitude", site.latitude())
                .param("longitude", site.longitude())
                .param("radiusMeters", radiusKm * 1000)
                .param("skills", Skill.codes(requiredSkills).toArray(String[]::new))
                .param("limit", limit + 1);
        if (after != null) {
            var cursor = Cursor.decode(after);
            query = query.param("afterDistance", distanceOf(cursor)).param("afterId", cursor.id());
        }
        return CursorPage.of(query.query(TechnicianSearch::row).list(), limit, Row::cursor)
                .map(Row::technician);
    }

    private static double distanceOf(Cursor cursor) {
        try {
            var distance = Double.parseDouble(cursor.sortValue());
            if (!Double.isFinite(distance) || distance < 0) {
                throw new InvalidCursorException();
            }
            return distance;
        } catch (NumberFormatException e) {
            throw new InvalidCursorException();
        }
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        var distanceMeters = rs.getDouble("distance_m");
        var technician = new NearbyTechnician(
                rs.getObject("id", UUID.class),
                rs.getString("full_name"),
                List.of((String[]) rs.getArray("skills").getArray()),
                rs.getBigDecimal("rating"),
                Math.round(distanceMeters / 100) / 10.0);
        // The cursor keeps the exact distance: a rounded one could skip or repeat technicians.
        // shortcut: base64 isn't secret, so a caller can read the exact distance of a page's last row and, by
        // searching from three sites, work out where that technician lives. Encrypt cursors (AES-GCM, server
        // key) before real technician data goes live.
        return new Row(technician, new Cursor(Double.toString(distanceMeters), technician.id()));
    }

    private record Row(NearbyTechnician technician, Cursor cursor) {}
}

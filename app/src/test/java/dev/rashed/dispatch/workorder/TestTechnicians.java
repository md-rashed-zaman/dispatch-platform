package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.TestTokens;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts technicians straight into the shared test database (technicians belong to no tenant). */
final class TestTechnicians {

    /** A verified technician living at the given spot, travelling up to 30 km. */
    static UUID verified(JdbcTemplate jdbc, double latitude, double longitude, String... skills) {
        var id = UUID.randomUUID();
        jdbc.update(
                """
                insert into technician (id, full_name, phone, skills, home_latitude, home_longitude,
                    service_radius_km, status)
                values (?, 'Test Technician', ?, ?, ?, ?, 30, 'VERIFIED')
                """,
                id,
                "+88019" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)),
                skills,
                latitude,
                longitude);
        return id;
    }

    /** An access token like Keycloak issues to the technician's user account. */
    static String token(UUID technicianId) {
        return TestTokens.token(
                c -> c.claim("roles", List.of("TECHNICIAN")).claim("technician_id", technicianId.toString()));
    }

    /** A skill code no other test uses, so technicians left in the shared database by other tests never match. */
    static String uniqueSkill() {
        return "test-" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);
    }

    private TestTechnicians() {}
}

package dev.rashed.dispatch.technician;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Skill;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class TechnicianRepositoryTests {

    @Autowired
    TechnicianRepository technicians;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesSkillsAsArrayAndDerivesGeographyFromLatLon() {
        var saved = technicians.save(Technician.register(
                "Rahim Uddin",
                "+8801712345678",
                List.of("pos-terminal", "networking"),
                new GeoPoint(23.7806, 90.4070),
                20));
        entityManager.flush();
        entityManager.clear();

        var loaded = technicians.findById(saved.id()).orElseThrow();
        assertThat(loaded.skills()).containsExactlyInAnyOrder(new Skill("networking"), new Skill("pos-terminal"));
        assertThat(loaded.home()).isEqualTo(new GeoPoint(23.7806, 90.4070));
        assertThat(loaded.status()).isEqualTo(TechnicianStatus.PENDING_VERIFICATION);

        // Postgres filled home_location itself; PostGIS stores (x = longitude, y = latitude).
        var xy = jdbc.queryForMap(
                "select ST_X(home_location::geometry) x, ST_Y(home_location::geometry) y from technician where id = ?",
                saved.id());
        assertThat(xy).containsEntry("x", 90.4070).containsEntry("y", 23.7806);
    }
}

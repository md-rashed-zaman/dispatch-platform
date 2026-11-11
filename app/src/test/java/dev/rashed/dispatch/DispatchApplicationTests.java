package dev.rashed.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DispatchApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayInstallsGeoAndVectorExtensions() {
        var extensions = jdbc.queryForList("select extname from pg_extension", String.class);
        assertThat(extensions).contains("postgis", "vector");
    }

}

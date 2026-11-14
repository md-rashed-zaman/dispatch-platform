package dev.rashed.dispatch.technician;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Technicians are placed at known distances and directions from a site in Gulshan. Each test uses its own
 * skill code, so technicians that other tests leave in the shared database never match.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TechnicianSearchTests {

    static final double SITE_LATITUDE = 23.7925;
    static final double SITE_LONGITUDE = 90.4125;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    final String skill = "test-" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);

    @Test
    void findsVerifiedTechniciansWithAllSkillsWhoseOwnRadiusReachesTheSite() throws Exception {
        var near = place(2_000, 0, 30, "VERIFIED", skill, "networking");
        var farther = place(5_000, 90, 30, "VERIFIED", skill, "networking");
        place(1_000, 180, 30, "PENDING_VERIFICATION", skill, "networking"); // not verified yet
        place(3_000, 270, 30, "VERIFIED", skill); // lacks networking
        place(25_000, 45, 30, "VERIFIED", skill, "networking"); // outside the 20 km search
        place(10_000, 135, 5, "VERIFIED", skill, "networking"); // only travels 5 km

        search(params("skill", skill, "skill", "networking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(near.toString(), farther.toString())))
                .andExpect(jsonPath("$.items[*].distanceKm").value(contains(2.0, 5.0)))
                .andExpect(jsonPath("$.items[0].skills").value(containsInAnyOrder("networking", skill)))
                .andExpect(jsonPath("$.next").value(nullValue()));
    }

    @Test
    void showsDistanceButNeverWhereTheTechnicianLives() throws Exception {
        place(2_000, 0, 30, "VERIFIED", skill);

        var body = search(params("skill", skill)).andReturn().getResponse().getContentAsString();

        Map<String, Object> technician = JsonPath.read(body, "$.items[0]");
        assertThat(technician).containsOnlyKeys("id", "fullName", "skills", "rating", "distanceKm");
    }

    /** The index only looks inside a box around the circle; nobody just inside the edge may fall out of it. */
    @Test
    void includesTechniciansJustInsideTheRadiusInEveryDirection() throws Exception {
        var inside = new ArrayList<String>();
        for (var azimuth = 0; azimuth < 360; azimuth += 15) {
            inside.add(placeOnSphere(19_990, azimuth, skill).toString());
            placeOnSphere(20_010, azimuth, skill);
        }

        search(params("skill", skill, "limit", "100"))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(inside.toArray())));
    }

    @Test
    void pagesThroughEveryTechnicianExactlyOnceEvenWhenDistancesTie() throws Exception {
        var expected = new ArrayList<UUID>();
        for (var distance : new int[] {1_000, 2_000, 2_000, 2_000, 3_000}) {
            expected.add(place(distance, 0, 30, "VERIFIED", skill)); // three live at the same spot
        }

        var seen = new ArrayList<String>();
        String after = null;
        var pages = 0;
        do {
            var request = params("skill", skill, "limit", "2");
            if (after != null) {
                request.param("after", after);
            }
            var body = search(request).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].id"));
            after = JsonPath.read(body, "$.next");
            pages++;
        } while (after != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).doesNotHaveDuplicates().hasSize(5);
        assertThat(seen.getFirst()).isEqualTo(expected.getFirst().toString());
        assertThat(seen.getLast()).isEqualTo(expected.getLast().toString());
        // Same distance: ordered by id. (As strings: Postgres compares uuids as unsigned bytes, Java's
        // UUID.compareTo as signed numbers, so they disagree on some ids.)
        assertThat(seen.subList(1, 4)).isSorted();
    }

    @Test
    void invalidSearchesAre400NamingTheProblem() throws Exception {
        search(get("/api/technicians/nearby")
                        .param("latitude", "91")
                        .param("longitude", "90.4")
                        .param("skill", skill))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("latitude"));
        search(params("skill", "Network Cables"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skill"));
        search(params("skill", skill, "radiusKm", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("radiusKm"));
        search(params("skill", skill, "after", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:invalid-cursor"));
        search(params()).andExpect(status().isBadRequest()); // no skill at all
    }

    @Test
    void techniciansCannotSearchOtherTechnicians() throws Exception {
        var technician = TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN")));

        mvc.perform(params("skill", skill).header(HttpHeaders.AUTHORIZATION, "Bearer " + technician))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:forbidden"));
    }

    /** Inserts a technician {@code meters} from the site in direction {@code azimuth} (0 = north, 90 = east). */
    private UUID place(int meters, int azimuth, int serviceRadiusKm, String status, String... skills) {
        var id = UUID.randomUUID();
        jdbc.update(
                """
                insert into technician (id, full_name, phone, skills, home_latitude, home_longitude,
                    service_radius_km, status)
                select ?, 'Test Technician', ?, ?, ST_Y(p::geometry), ST_X(p::geometry), ?, ?
                from (select ST_Project(ST_Point(?, ?, 4326)::geography, ?, radians(?)) p) projected
                """,
                id,
                randomPhone(),
                skills,
                serviceRadiusKm,
                status,
                SITE_LONGITUDE,
                SITE_LATITUDE,
                meters,
                azimuth);
        return id;
    }

    /**
     * Like {@link #place}, but measured on a sphere, the way the search measures ({@code <->}), so a point can
     * sit 10 m from the edge. (ST_Project measures on the ellipsoid; the two differ by up to 0.6%.)
     */
    private UUID placeOnSphere(int meters, int azimuth, String skill) {
        var distance = meters / 6_371_008.8; // radians, on PostGIS's mean Earth radius
        var bearing = Math.toRadians(azimuth);
        var lat1 = Math.toRadians(SITE_LATITUDE);
        var lat2 = Math.asin(
                Math.sin(lat1) * Math.cos(distance) + Math.cos(lat1) * Math.sin(distance) * Math.cos(bearing));
        var lon2 = Math.toRadians(SITE_LONGITUDE)
                + Math.atan2(
                        Math.sin(bearing) * Math.sin(distance) * Math.cos(lat1),
                        Math.cos(distance) - Math.sin(lat1) * Math.sin(lat2));
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into technician (id, full_name, phone, skills, home_latitude, home_longitude,
                    service_radius_km, status)
                values (?, 'Test Technician', ?, ?, ?, ?, 30, 'VERIFIED')
                """, id, randomPhone(), new String[] {skill}, Math.toDegrees(lat2), Math.toDegrees(lon2));
        return id;
    }

    private static String randomPhone() {
        return "+88019" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static MockHttpServletRequestBuilder params(String... nameValuePairs) {
        var request = get("/api/technicians/nearby")
                .param("latitude", String.valueOf(SITE_LATITUDE))
                .param("longitude", String.valueOf(SITE_LONGITUDE));
        for (var i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return request;
    }

    private ResultActions search(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.dispatcher()));
    }
}

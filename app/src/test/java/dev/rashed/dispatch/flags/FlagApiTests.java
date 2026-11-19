package dev.rashed.dispatch.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Managing a flag through the API, and reading it through OpenFeature, as the rest of the app does. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FlagApiTests {

    static final String FLAG = "ai.auto-approve";

    @Autowired
    MockMvc mvc;

    @Autowired
    Client flags;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    final String admin = TestTokens.platformAdmin();

    @Test
    void aChangeNeedsTheCurrentVersionAndIsRecordedWithItsReason() throws Exception {
        var tenant = UUID.randomUUID();
        var etag = etag();

        change(etag, 0, List.of(tenant), "pilot with one company")
                .andExpect(status().isOk())
                .andExpect(
                        header().string(HttpHeaders.ETAG, "\"" + (Long.parseLong(etag.replace("\"", "")) + 1) + "\""));
        change(etag, 50, List.of(), "someone else's stale copy").andExpect(status().isPreconditionFailed());
        mvc.perform(put("/api/flags/{key}", FLAG)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(50, List.of(), "no If-Match")))
                .andExpect(status().isPreconditionRequired());

        mvc.perform(get("/api/flags/{key}/changes", FLAG).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(jsonPath("$[0].reason").value("pilot with one company"))
                .andExpect(jsonPath("$[0].after").value(org.hamcrest.Matchers.containsString(tenant.toString())));

        // This instance sees the change at once, through the OpenFeature client the app uses.
        var details = flags.getBooleanDetails(FLAG, false, new ImmutableContext(tenant.toString()));
        assertThat(details.getValue()).isTrue();
        assertThat(details.getVariant()).isEqualTo("on");
        assertThat(details.getReason()).isEqualTo("TARGETING_MATCH");
        assertThat(flags.getBooleanValue(
                        FLAG, false, new ImmutableContext(UUID.randomUUID().toString())))
                .as("everyone else, at 0%%")
                .isFalse();
        mvc.perform(get("/api/flags/{key}/explain", FLAG)
                        .param("tenantId", tenant.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(jsonPath("$.variant").value("on"))
                .andExpect(jsonPath("$.reason").value("TARGETING_MATCH"));
    }

    @Test
    void anAdminReadsWhatIsReallyThereNotThisInstancesSnapshot() throws Exception {
        etag(); // this instance's snapshot is loaded
        // Another instance changes the flag: this one's snapshot learns it only at its next refresh.
        jdbc.update("update feature_flag set rollout_percent = 7, version = version + 1 where key = ?", FLAG);
        var version = jdbc.queryForObject("select version from feature_flag where key = ?", Long.class, FLAG);

        mvc.perform(get("/api/flags/{key}", FLAG).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + version + "\""))
                .andExpect(jsonPath("$.rolloutPercent").value(7));
        mvc.perform(get("/api/flags", FLAG).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.key == '%s')].rolloutPercent".formatted(FLAG))
                        .value(7));
        // So an edit made from that read goes through instead of a 412 (step 5.2: two pods, half the saves failed).
        change(etag(), 7, List.of(), "edit after another instance's change").andExpect(status().isOk());
    }

    @Test
    void anUnknownFlagIsTheDefaultValueAndOnlyPlatformAdminsManageFlags() throws Exception {
        var details = flags.getBooleanDetails(
                "no.such-flag", false, new ImmutableContext(UUID.randomUUID().toString()));
        assertThat(details.getValue()).isFalse();
        assertThat(details.getErrorCode()).hasToString("FLAG_NOT_FOUND");

        var dispatcher = TestTokens.dispatcher();
        mvc.perform(get("/api/flags").header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isForbidden());
        change(etag(), 120, List.of(), "too much").andExpect(status().isBadRequest());
    }

    private String etag() throws Exception {
        return mvc.perform(get("/api/flags/{key}", FLAG).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    private org.springframework.test.web.servlet.ResultActions change(
            String etag, int percent, List<UUID> allow, String reason) throws Exception {
        return mvc.perform(put("/api/flags/{key}", FLAG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(percent, allow, reason)));
    }

    private static String body(int percent, List<UUID> allow, String reason) {
        var ids = allow.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return """
                {"enabled": true, "rolloutPercent": %d, "allowTenants": [%s], "denyTenants": [], "reason": "%s"}""".formatted(percent, ids, reason);
    }
}

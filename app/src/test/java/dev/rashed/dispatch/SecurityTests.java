package dev.rashed.dispatch;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 401 = "I don't know who you are" (no token, or a token that fails verification).
 * 403 = "I know who you are, and you may not do this" (valid token, missing role).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityTests {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void validTokenIdentifiesCallerWithTenantAndDispatchRolesOnly() throws Exception {
        call("/api/me", TestTokens.dispatcher())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TestTokens.MEGHNA_BANK.toString()))
                .andExpect(jsonPath("$.roles", contains("DISPATCHER"))); // offline_access dropped
    }

    @Test
    void expiredTokenIs401() throws Exception {
        // Spring allows 60 s of clock skew between servers, so expire it well before that.
        var expired =
                TestTokens.token(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120))));
        call("/api/me", expired).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByUnknownKeyIs401() throws Exception {
        call("/api/me", TestTokens.signedByUnknownKey()).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIs401() throws Exception {
        call("/api/me", TestTokens.token(c -> c.issuer("http://evil.example/realms/dispatch")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForAnotherAudienceIs401() throws Exception {
        call("/api/me", TestTokens.token(c -> c.audience("some-other-api"))).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        // Change one character of the payload: the signature no longer matches.
        var parts = TestTokens.dispatcher().split("\\.");
        var tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        call("/api/me", tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void dispatcherCannotListCompanies() throws Exception {
        call("/api/companies", TestTokens.dispatcher()).andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCanListCompanies() throws Exception {
        call("/api/companies", TestTokens.platformAdmin()).andExpect(status().isOk());
    }

    private ResultActions call(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}

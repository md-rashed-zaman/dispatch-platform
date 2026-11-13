package dev.rashed.dispatch;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.core.io.ClassPathResource;

/**
 * Builds access tokens shaped like Keycloak's and signs them with the test key, whose public half the
 * app trusts in tests (src/test/resources/application.properties).
 */
public final class TestTokens {

    public static final String ISSUER = "http://localhost:8180/realms/dispatch";
    public static final UUID MEGHNA_BANK = UUID.fromString("01930000-0000-7000-8000-000000000001");

    private static final PrivateKey TEST_KEY = readPrivateKey("jwt/test-private.pem");

    public static String dispatcher() {
        return token(c ->
                c.claim("roles", List.of("DISPATCHER", "offline_access")).claim("tenant_id", MEGHNA_BANK.toString()));
    }

    public static String platformAdmin() {
        return token(c -> c.claim("roles", List.of("PLATFORM_ADMIN")));
    }

    /** A valid token; {@code customize} adds or overrides claims. */
    public static String token(UnaryOperator<JWTClaimsSet.Builder> customize) {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("dispatch-api")
                .subject(UUID.randomUUID().toString())
                .claim("preferred_username", "test.user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
        return sign(customize.apply(claims).build(), TEST_KEY);
    }

    /** Correctly shaped token signed by a key the app has never seen, like a forged token. */
    public static String signedByUnknownKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return sign(
                    new JWTClaimsSet.Builder()
                            .issuer(ISSUER)
                            .audience("dispatch-api")
                            .subject(UUID.randomUUID().toString())
                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                            .claim("roles", List.of("PLATFORM_ADMIN"))
                            .build(),
                    generator.generateKeyPair().getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWTClaimsSet claims, PrivateKey key) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivateKey readPrivateKey(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            var pem = new String(in.readAllBytes())
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestTokens() {}
}

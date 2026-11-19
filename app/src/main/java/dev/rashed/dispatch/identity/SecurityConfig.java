package dev.rashed.dispatch.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every request must carry a valid Keycloak access token, except health checks, API docs and the payment
 * provider's callbacks. Token
 * checks (signature via Keycloak's public keys, issuer, audience, expiry) and the role mapping are
 * configured in application.yml.
 */
@Configuration
@EnableMethodSecurity // enables @PreAuthorize on service methods
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${dispatch.oauth.issuer-uri}") String issuerUri)
            throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**",
                                // The same probes on the main port (step 5.1): Kubernetes sends no token.
                                "/livez",
                                "/readyz",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                // The payment provider and the customer's browser after paying can't sign in;
                                // these only name a payment to check, never prove one (ADR 0015).
                                "/api/payments/sslcommerz/**",
                                // Local stand-in for the provider; only exists when dispatch.payments.sslcommerz.fake
                                "/fake-sslcommerz/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        // OAuth 2.0 Protected Resource Metadata (RFC 9728) at /.well-known/oauth-protected-resource,
                        // linked from every 401: how an MCP client (step 4.5) finds where to sign in.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                resource -> resource.authorizationServer(issuerUri))))
                // No server session: each request proves itself with its token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF attacks ride on cookies the browser sends automatically. Bearer tokens aren't sent
                // automatically, so CSRF protection has nothing to protect here.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Rejects tokens from any other issuer (another Keycloak realm or server), even if correctly signed.
     * Spring Boot adds every validator bean to its token decoder, next to the expiry and audience checks.
     */
    @Bean
    OAuth2TokenValidator<Jwt> issuerValidator(@Value("${dispatch.oauth.issuer-uri}") String issuerUri) {
        return new JwtIssuerValidator(issuerUri);
    }
}

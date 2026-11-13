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
 * Every request must carry a valid Keycloak access token, except health checks and API docs. Token
 * checks (signature via Keycloak's public keys, issuer, audience, expiry) and the role mapping are
 * configured in application.yml.
 */
@Configuration
@EnableMethodSecurity // enables @PreAuthorize on service methods
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
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

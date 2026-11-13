package dev.rashed.dispatch;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(title = "Dispatch API", version = "0.1", description = "Multi-tenant field service marketplace"),
        security = @SecurityRequirement(name = "keycloak"))
// Swagger UI's "Authorize" button logs in with Keycloak (authorization code + PKCE).
@SecurityScheme(
        name = "keycloak",
        type = SecuritySchemeType.OAUTH2,
        flows =
                @OAuthFlows(
                        authorizationCode =
                                @OAuthFlow(
                                        authorizationUrl = "${dispatch.oauth.issuer-uri}/protocol/openid-connect/auth",
                                        tokenUrl = "${dispatch.oauth.issuer-uri}/protocol/openid-connect/token")))
public class DispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchApplication.class, args);
    }
}

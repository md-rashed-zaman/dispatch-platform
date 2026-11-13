package dev.rashed.dispatch.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Me", description = "The signed-in caller")
@RestController
@RequestMapping("/api/me")
class MeController {

    @Operation(summary = "Who am I: the caller as the API sees them")
    @GetMapping
    CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return CurrentUser.from(jwt);
    }
}

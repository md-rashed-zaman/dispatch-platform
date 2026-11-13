package dev.rashed.dispatch.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The verified caller, read from the access token's claims.
 *
 * @param id Keycloak user id ({@code sub} claim)
 * @param tenantId the user's company, or null for technicians and platform admins ({@code tenant_id})
 * @param roles Dispatch roles only; Keycloak's built-in roles like {@code offline_access} are dropped
 */
public record CurrentUser(UUID id, String username, UUID tenantId, Set<Role> roles) {

    public static CurrentUser from(Jwt jwt) {
        var tenantId = jwt.getClaimAsString("tenant_id");
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("preferred_username"),
                tenantId == null ? null : UUID.fromString(tenantId),
                knownRoles(jwt.getClaimAsStringList("roles")));
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    private static Set<Role> knownRoles(List<String> claim) {
        if (claim == null) {
            return Set.of();
        }
        var names = Arrays.stream(Role.values()).map(Role::name).collect(Collectors.toSet());
        return claim.stream()
                .filter(names::contains)
                .map(Role::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }
}

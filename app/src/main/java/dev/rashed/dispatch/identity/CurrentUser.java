package dev.rashed.dispatch.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The verified caller, read from the access token's claims.
 *
 * @param id Keycloak user id ({@code sub} claim)
 * @param tenantId the user's company, or null for technicians and platform admins ({@code tenant_id})
 * @param technicianId the user's technician profile, or null for everyone else ({@code technician_id})
 * @param roles Dispatch roles only; Keycloak's built-in roles like {@code offline_access} are dropped
 */
public record CurrentUser(
        UUID id,
        String username,
        @Nullable UUID tenantId,
        @Nullable UUID technicianId,
        Set<Role> roles) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("preferred_username"),
                uuidClaim(jwt, "tenant_id"),
                uuidClaim(jwt, "technician_id"),
                knownRoles(jwt.getClaimAsStringList("roles")));
    }

    /** The caller, if the current thread handles a request with a verified token. */
    public static Optional<CurrentUser> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                ? Optional.of(from(jwt))
                : Optional.empty();
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    private static @Nullable UUID uuidClaim(Jwt jwt, String name) {
        var value = jwt.getClaimAsString(name);
        return value == null ? null : UUID.fromString(value);
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

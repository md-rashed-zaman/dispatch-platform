package dev.rashed.dispatch.identity;

/** Realm roles defined in Keycloak (infra/compose/keycloak). Spring sees them as {@code ROLE_<name>}. */
public enum Role {
    CLIENT_ADMIN,
    DISPATCHER,
    TECHNICIAN,
    PLATFORM_ADMIN
}

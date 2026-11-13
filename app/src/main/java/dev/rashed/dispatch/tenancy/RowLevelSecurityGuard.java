package dev.rashed.dispatch.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Row-level security silently does nothing for superusers, roles with BYPASSRLS and the table's owner.
 * Connecting the app as one of those (an easy mistake in a new environment) would turn off the database
 * layer of tenant isolation without any error, so the app refuses to start instead.
 */
final class RowLevelSecurityGuard {

    static void verify(JdbcTemplate jdbc) {
        var row = jdbc.queryForMap("""
                select current_user as name,
                       rolsuper or rolbypassrls
                         or pg_has_role(current_user, (select relowner from pg_class where oid = 'work_order'::regclass),
                                        'USAGE') as bypasses
                from pg_roles where rolname = current_user
                """);
        if (Boolean.TRUE.equals(row.get("bypasses"))) {
            throw new IllegalStateException("Database user '" + row.get("name")
                    + "' bypasses row-level security (superuser, BYPASSRLS or table owner)."
                    + " Connect the app as dispatch_app; only Flyway uses the owner (spring.flyway.user).");
        }
    }

    private RowLevelSecurityGuard() {}
}

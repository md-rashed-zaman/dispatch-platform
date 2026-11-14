package dev.rashed.dispatch.tenancy;

import dev.rashed.dispatch.identity.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Two independent layers keep tenants apart (ADR 0003):
 *
 * <ol>
 *   <li>Hibernate adds {@code tenant_id = <current tenant>} to every query on {@code @TenantId} entities and
 *       fills that column on insert.
 *   <li>Postgres row-level security (migration V4) filters the same rows inside the database, so native SQL
 *       or a forgotten annotation still can't cross tenants. Every transaction starts by telling Postgres
 *       the tenant (and, for technicians, the technician) with transaction-local settings, which disappear at commit and so can't leak to the
 *       next request that borrows the same pooled connection.
 * </ol>
 *
 * Without a tenant both layers return no rows: Hibernate filters on a tenant id that matches nothing, and
 * Postgres sees no setting.
 */
@Configuration
class TenancyConfig {

    /** Matches no company, so "no tenant" can never read or write tenant data. */
    static final UUID NO_TENANT = new UUID(0, 0);

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolver() {
        return properties -> properties.put(
                MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new CurrentTenantIdentifierResolver<UUID>() {
                    @Override
                    public UUID resolveCurrentTenantIdentifier() {
                        return TenantContext.current().orElse(NO_TENANT);
                    }

                    @Override
                    public boolean validateExistingCurrentSessions() {
                        return false; // Spring opens one session per transaction; there are no "current sessions"
                    }
                });
    }

    /** Replaces Spring Boot's JPA transaction manager with the same one plus the tenant setting. */
    @Bean
    PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory, ObjectProvider<TransactionManagerCustomizers> customizers) {
        var transactionManager = new JpaTransactionManager(entityManagerFactory) {
            @Override
            public void afterPropertiesSet() {
                super.afterPropertiesSet(); // copies the factory's own dialect, so ours goes in after it
                setJpaDialect(new TenantAwareJpaDialect());
            }
        };
        customizers.ifAvailable(c -> c.customize(transactionManager));
        return transactionManager;
    }

    /** Fails startup if the database user would skip the row-level security policies. */
    @Bean
    ApplicationRunner rowLevelSecurityGuard(JdbcTemplate jdbc) {
        return args -> RowLevelSecurityGuard.verify(jdbc);
    }

    static final class TenantAwareJpaDialect extends HibernateJpaDialect {

        // Runs inside the transaction begin, so if it fails Spring rolls back and closes the session.
        @Override
        public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
                throws SQLException {
            var transactionData = super.beginTransaction(entityManager, definition);
            var session = entityManager.unwrap(Session.class);
            TenantContext.current().ifPresent(tenantId -> setLocal(session, "app.tenant_id", tenantId));
            // Technicians belong to no tenant; row-level security knows them by their technician id (V7).
            CurrentUser.current()
                    .map(CurrentUser::technicianId)
                    .ifPresent(technicianId -> setLocal(session, "app.technician_id", technicianId));
            return transactionData;
        }

        private static void setLocal(Session session, String name, UUID value) {
            session.doWork(connection -> {
                // true = local to this transaction (SET LOCAL), never the whole pooled connection
                try (var statement = connection.prepareStatement("select set_config(?, ?, true)")) {
                    statement.setString(1, name);
                    statement.setString(2, value.toString());
                    statement.execute();
                }
            });
        }
    }
}

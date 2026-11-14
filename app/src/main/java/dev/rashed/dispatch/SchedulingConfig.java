package dev.rashed.dispatch;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs, and how they run once when there are several app instances (T22).
 *
 * <p>{@code @Scheduled} runs on every instance. A job that must run once per round also gets
 * {@code @SchedulerLock}: the instance that takes the job's row in the {@code shedlock} table runs it, the
 * others skip the round. Jobs that split work on their own ({@code FOR UPDATE SKIP LOCKED}, an advisory lock)
 * or that are per instance (SSE keep-alive) don't need it.
 *
 * <p>The lock is not the only guard: if a job outlives {@code lockAtMostFor}, a second instance can start it.
 * Every locked job is also safe to run twice.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource, @Value("${dispatch.instance-name}") String instance) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // lock times from PostgreSQL's clock, not each instance's
                // Which instance holds it. The default is the host name, which instances can share.
                .withLockedByValue(instance)
                .build());
    }
}

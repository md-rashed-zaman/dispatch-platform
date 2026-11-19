package dev.rashed.dispatch.flags;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.StaleVersionException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Managing flags (platform admins). Every change is a conditional update on the version plus an audit row. */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class FlagService {

    private static final Logger log = LoggerFactory.getLogger(FlagService.class);

    public record Change(
            boolean enabled, int rolloutPercent, Set<UUID> allowTenants, Set<UUID> denyTenants, String reason) {}

    public record ChangeRecord(
            long id,
            @Nullable UUID changedBy,
            String reason,
            @Nullable String before,
            String after,
            Instant changedAt) {}

    /** What a flag means for one company, and why: support's first question. */
    public record Explanation(
            String flag,
            UUID tenant,
            String variant,
            Flag.Reason reason,
            @Nullable Integer bucket,
            int rolloutPercent) {}

    private final FlagStore store;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    FlagService(FlagStore store, JdbcClient jdbc, JsonMapper json) {
        this.store = store;
        this.jdbc = jdbc;
        this.json = json;
    }

    // The admin reads come from the database, not this instance's snapshot: another instance's change reaches the
    // snapshot only at its next refresh, and an edit started from a stale read (its ETag) would fail with 412.
    // Evaluation (DispatchFlagProvider) keeps the snapshot.
    public List<Flag> all() {
        return store.refresh().values().stream()
                .sorted(java.util.Comparator.comparing(Flag::key))
                .toList();
    }

    public Flag get(String key) {
        return current(key);
    }

    @Transactional
    public Flag change(String key, long expectedVersion, Change change) {
        var before = current(key);
        if (before.version() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, before.version());
        }
        var after = new Flag(
                key,
                before.description(),
                change.enabled(),
                change.rolloutPercent(),
                change.allowTenants(),
                change.denyTenants(),
                before.version() + 1,
                Instant.now(),
                CurrentUser.current().map(CurrentUser::id).orElse(null));
        var updated = jdbc.sql("""
                        UPDATE feature_flag
                        SET enabled = :enabled, rollout_percent = :percent, allow_tenants = CAST(:allow AS uuid[]),
                            deny_tenants = CAST(:deny AS uuid[]), version = version + 1, updated_at = now(), updated_by = :by
                        WHERE key = :key AND version = :version
                        """)
                .param("enabled", after.enabled())
                .param("percent", after.rolloutPercent())
                .param("allow", array(after.allowTenants()))
                .param("deny", array(after.denyTenants()))
                .param("by", after.updatedBy())
                .param("key", key)
                .param("version", expectedVersion)
                .update();
        if (updated == 0) { // changed between our read and the update
            throw new StaleVersionException(expectedVersion, current(key).version());
        }
        jdbc.sql("""
                        INSERT INTO feature_flag_change (flag_key, changed_by, reason, before, after)
                        VALUES (:key, :by, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("key", key)
                .param("by", after.updatedBy())
                .param("reason", change.reason())
                .param("before", rules(before))
                .param("after", rules(after))
                .update();
        // This instance sees it at once, after the commit; the others within the refresh interval.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                store.refresh();
            }
        });
        log.info("Flag {} changed by {}: {} ({})", key, after.updatedBy(), rules(after), change.reason());
        return current(key);
    }

    @Transactional(readOnly = true)
    public List<ChangeRecord> changes(String key) {
        get(key);
        return jdbc.sql("""
                        SELECT id, changed_by, reason, before::text AS before, after::text AS after, changed_at
                        FROM feature_flag_change WHERE flag_key = :key ORDER BY changed_at DESC, id DESC LIMIT 100
                        """)
                .param("key", key)
                .query((rs, row) -> new ChangeRecord(
                        rs.getLong("id"),
                        rs.getObject("changed_by", UUID.class),
                        rs.getString("reason"),
                        rs.getString("before"),
                        rs.getString("after"),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
    }

    public Explanation explain(String key, UUID tenant) {
        var flag = get(key);
        var evaluation = flag.evaluate(tenant);
        return new Explanation(
                key, tenant, evaluation.variant(), evaluation.reason(), evaluation.bucket(), flag.rolloutPercent());
    }

    /** Read from the database, not the snapshot: a change must start from what's really there. */
    private Flag current(String key) {
        return jdbc.sql("""
                        SELECT key, description, enabled, rollout_percent, allow_tenants, deny_tenants, version,
                               updated_at, updated_by
                        FROM feature_flag WHERE key = :key
                        """)
                .param("key", key)
                .query(FlagStore::flag)
                .optional()
                .orElseThrow(() -> new NotFoundException("Flag", key));
    }

    private String rules(Flag flag) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("enabled", flag.enabled());
        rules.put("rolloutPercent", flag.rolloutPercent());
        rules.put(
                "allowTenants",
                flag.allowTenants().stream().map(UUID::toString).sorted().toList());
        rules.put(
                "denyTenants",
                flag.denyTenants().stream().map(UUID::toString).sorted().toList());
        return json.writeValueAsString(rules);
    }

    private static String array(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
}

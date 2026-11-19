package dev.rashed.dispatch.flags;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flags read from the database into an in-memory snapshot, refreshed every few seconds: evaluating a flag on
 * every request costs a map lookup, not a query. Another instance's change reaches this one within the refresh
 * interval; this instance's own changes at once.
 *
 * <p>If the database can't be read, the last snapshot stays: flags keep their last known values rather than all
 * switching off (or on) because of an outage.
 */
@Component
class FlagStore {

    private static final Logger log = LoggerFactory.getLogger(FlagStore.class);

    private final JdbcClient jdbc;
    private final AtomicReference<@Nullable Map<String, Flag>> snapshot = new AtomicReference<>();

    FlagStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Flag> get(String key) {
        var flags = snapshot.get();
        if (flags == null) {
            flags = refresh();
        }
        return Optional.ofNullable(flags.get(key));
    }

    List<Flag> all() {
        var flags = snapshot.get();
        return List.copyOf((flags == null ? refresh() : flags).values());
    }

    @Scheduled(
            fixedDelayString = "${dispatch.flags.refresh-interval:PT5S}",
            initialDelayString = "${dispatch.flags.refresh-interval:PT5S}")
    void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Couldn't refresh feature flags, keeping the last ones: {}", e.toString());
        }
    }

    Map<String, Flag> refresh() {
        var flags = jdbc.sql("""
                        SELECT key, description, enabled, rollout_percent, allow_tenants, deny_tenants, version,
                               updated_at, updated_by
                        FROM feature_flag
                        """).query(FlagStore::flag).list().stream()
                .collect(Collectors.toUnmodifiableMap(Flag::key, Function.identity()));
        snapshot.set(flags);
        return flags;
    }

    static Flag flag(ResultSet rs, int row) throws SQLException {
        return new Flag(
                rs.getString("key"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getInt("rollout_percent"),
                uuids(rs.getArray("allow_tenants")),
                uuids(rs.getArray("deny_tenants")),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    private static Set<UUID> uuids(@Nullable Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(o -> (UUID) o).collect(Collectors.toUnmodifiableSet());
    }
}

package dev.rashed.dispatch.technician;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The skill codes verified technicians actually have. A work order asking for any other skill can never be
 * matched, so this is the closed list AI suggestions must choose from (T27: never let a model invent ids).
 */
@Service
public class SkillCatalog {

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private volatile Snapshot snapshot;

    private record Snapshot(Set<String> codes, long loadedAtNanos) {}

    SkillCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sorted. Reloaded at most every 10 minutes: new skills appear rarely, and a little late is fine. */
    public Set<String> codes() {
        var current = snapshot;
        if (current == null || System.nanoTime() - current.loadedAtNanos() > REFRESH_AFTER.toNanos()) {
            // About 95 ms over 50,000 technicians (11 skills), hence the memo. Two threads may both reload at
            // expiry; harmless.
            List<String> codes = jdbc.sql(
                            "SELECT DISTINCT unnest(skills) FROM technician WHERE status = 'VERIFIED' ORDER BY 1")
                    .query(String.class)
                    .list();
            current = new Snapshot(Collections.unmodifiableSortedSet(new TreeSet<>(codes)), System.nanoTime());
            snapshot = current;
        }
        return current.codes();
    }
}

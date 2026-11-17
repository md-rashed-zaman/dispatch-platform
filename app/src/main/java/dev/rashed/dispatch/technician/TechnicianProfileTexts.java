package dev.rashed.dispatch.technician;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Technician profiles as the text matching embeds (step 3.4): the skills and the bio. For system callers (the
 * embedding consumer and backfill), so no role check; nothing personal is in it (no phone, no location).
 */
@Service
public class TechnicianProfileTexts {

    public record ProfileText(UUID technicianId, String text) {}

    private final JdbcClient jdbc;

    TechnicianProfileTexts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ProfileText> texts(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, skills, bio FROM technician WHERE id = ANY(:ids) ORDER BY id")
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, n) -> new ProfileText(
                        rs.getObject("id", UUID.class),
                        text(List.of((String[]) rs.getArray("skills").getArray()), rs.getString("bio"))))
                .list();
    }

    /** Technician ids in id order after {@code after} (null: from the start), for walking all of them in pages. */
    @Transactional(readOnly = true)
    public List<UUID> idsAfter(@Nullable UUID after, int limit) {
        return jdbc.sql(
                        after == null
                                ? "SELECT id FROM technician ORDER BY id LIMIT :limit"
                                : "SELECT id FROM technician WHERE id > :after ORDER BY id LIMIT :limit")
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** "Skills: cctv, networking. About: ..." Skills sorted, so the same profile is always the same text. */
    static String text(Collection<String> skills, @Nullable String bio) {
        var text = "Skills: " + String.join(", ", new TreeSet<>(skills)) + ".";
        return bio == null || bio.isBlank() ? text : text + " About: " + bio.strip();
    }
}

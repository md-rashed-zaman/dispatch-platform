package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.technician.TechnicianProfileTexts;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Embeds every technician without a vector from the current model (step 3.4, T30: "how do you re-embed 50,000
 * profiles when the model changes?"). Walks technicians in id order, one batch per round, paced under the
 * embedding quota. Resumable by design: what's done is in the table, so after a restart a round skips embedded
 * technicians with one cheap query per page.
 *
 * <p>The free tier counts each text as a request: 100 a minute and 1,000 a day. So one batch of 90 a minute, and
 * at most {@code daily-limit} a day: re-embedding 50,000 profiles takes about 55 days on the free tier and a few
 * minutes on a paid one (docs/ai-limits.md). A changed model name makes every stored vector "missing", so changing
 * {@code dispatch.ai.embedding.model} re-embeds everyone; until then ranking leaves out their semantic part.
 */
@Component
class EmbeddingBackfill {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfill.class);

    private final TechnicianProfileTexts profiles;
    private final EmbeddingStore store;
    private final TechnicianEmbedder embedder;
    private final Embeddings embeddings;
    private final int batchSize;
    private final int dailyLimit;

    /** Where the last batch ended; null starts from the first technician. Per instance: see run(). */
    private volatile @Nullable UUID position;

    // shortcut: counted per instance and in memory (a restart forgets today's count); the provider's own 429 is
    // the hard stop. Count in the database when several instances run the backfill.
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private int embeddedToday;

    EmbeddingBackfill(
            TechnicianProfileTexts profiles,
            EmbeddingStore store,
            TechnicianEmbedder embedder,
            Embeddings embeddings,
            @Value("${dispatch.matching.backfill.batch-size:90}") int batchSize,
            @Value("${dispatch.matching.backfill.daily-limit:900}") int dailyLimit) {
        this.profiles = profiles;
        this.store = store;
        this.embedder = embedder;
        this.embeddings = embeddings;
        this.batchSize = batchSize;
        this.dailyLimit = dailyLimit;
    }

    // One instance at a time (ShedLock). If the lock moves to another instance, that one starts from the first
    // technician: it re-reads ids it doesn't need, but embeds nothing twice (the table says what's done).
    @Scheduled(
            fixedDelayString = "${dispatch.matching.backfill.interval:PT65S}",
            initialDelayString = "${dispatch.matching.backfill.initial-delay:PT30S}")
    @SchedulerLock(name = "embedding-backfill", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public synchronized void run() {
        var today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            embeddedToday = 0;
        }
        if (embeddedToday >= dailyLimit) {
            return;
        }
        // Skip pages that are already done (cheap); embed one batch per round at most (paced).
        var from = position;
        for (int pages = 0; pages < 100; pages++) {
            var ids = profiles.idsAfter(from, batchSize);
            if (ids.isEmpty()) {
                position = null; // a pass is complete; the next round starts another, which finds little to do
                return;
            }
            var missing = missing(ids);
            if (missing.isEmpty()) {
                from = ids.getLast();
                position = from;
                continue;
            }
            int embedded = embedder.embed(missing);
            if (embedded < 0) {
                return; // provider unavailable: the same page next round
            }
            embeddedToday += embedded;
            position = ids.getLast();
            log.info(
                    "Embedding backfill: {} technicians embedded, {} today ({})",
                    embedded,
                    embeddedToday,
                    embeddings.model());
            return;
        }
    }

    private List<UUID> missing(List<UUID> ids) {
        var done = store.technicianHashes(ids, embeddings.model());
        return ids.stream().filter(id -> !done.containsKey(id)).toList();
    }
}

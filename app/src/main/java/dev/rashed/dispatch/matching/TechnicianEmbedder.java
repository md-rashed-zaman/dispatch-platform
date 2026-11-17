package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.technician.TechnicianProfileTexts;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps technician vectors current (step 3.4). A profile change arrives on Kafka; the current profile text is
 * embedded if it differs from what the stored vector was made from.
 *
 * <p>Idempotent without a processed-event table: the same event twice, or an old event after a newer one, finds
 * the stored hash equal to the current text's and does nothing. If the provider is down, the stale vector is
 * deleted (a vector of old text ranks the technician wrongly) and the backfill embeds the profile later.
 */
@Component
class TechnicianEmbedder {

    private static final Logger log = LoggerFactory.getLogger(TechnicianEmbedder.class);

    static final String CONSUMER = "matching-embedder";
    static final String FEATURE = "technician-embedding";

    private final TechnicianProfileTexts profiles;
    private final EmbeddingStore store;
    private final Embeddings embeddings;
    private final JsonMapper json;

    TechnicianEmbedder(TechnicianProfileTexts profiles, EmbeddingStore store, Embeddings embeddings, JsonMapper json) {
        this.profiles = profiles;
        this.store = store;
        this.embeddings = embeddings;
        this.json = json;
    }

    @KafkaListener(
            id = CONSUMER,
            groupId = CONSUMER,
            topics = Topics.TECHNICIAN_EVENTS,
            containerFactory = "embedderListenerFactory",
            autoStartup = "${dispatch.matching.embedder-enabled:${dispatch.notification.consumer-enabled:true}}")
    void on(ConsumerRecord<String, String> record) {
        var technicianId = UUID.fromString(json.readTree(record.value())
                .path("payload")
                .path("technicianId")
                .asText());
        embed(List.of(technicianId));
    }

    /**
     * Embeds the profiles whose text changed since their vector was made. Returns how many it embedded, or -1
     * when the provider couldn't answer (nothing changed; try again later).
     */
    int embed(Collection<UUID> technicianIds) {
        var texts = profiles.texts(technicianIds);
        var hashes = store.technicianHashes(technicianIds, embeddings.model());
        var stale = texts.stream()
                .filter(p -> !EmbeddingStore.hash(p.text()).equals(hashes.get(p.technicianId())))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        var vectors = embeddings.embed(
                FEATURE,
                Embeddings.Kind.DOCUMENT,
                stale.stream().map(TechnicianProfileTexts.ProfileText::text).toList());
        if (vectors.isEmpty()) {
            stale.forEach(p -> {
                if (hashes.containsKey(p.technicianId())) {
                    store.deleteTechnician(p.technicianId());
                }
            });
            log.warn("Couldn't embed {} technician profiles now; the backfill will retry", stale.size());
            return -1;
        }
        for (int i = 0; i < stale.size(); i++) {
            var profile = stale.get(i);
            store.saveTechnician(
                    profile.technicianId(),
                    vectors.get().get(i),
                    embeddings.model(),
                    EmbeddingStore.hash(profile.text()));
        }
        return stale.size();
    }
}

package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.technician.NearbyTechnician;
import dev.rashed.dispatch.technician.TechnicianSearch;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Ranks nearby technicians by a weighted score (step 3.4, ADR 0013, T30):
 *
 * <ul>
 *   <li><b>semantic</b>: how close the technician's profile is in meaning to the job text (cosine similarity of
 *       the two vectors, scaled to 0-1 across this pool);
 *   <li><b>skills</b>: the share of required skills the technician has;
 *   <li><b>distance</b>: 1 at the site, 0 at the edge of the search radius;
 *   <li><b>rating</b>: out of 5; 3 for a technician not yet rated;
 *   <li><b>reliability</b>: completed out of assigned jobs, smoothed ((completed + 1) / (assigned + 2)) so one
 *       job doesn't make anyone perfect or hopeless.
 * </ul>
 *
 * Geo filter first, then exact cosine on that pool (a "pre-filter"): the few hundred candidates are compared
 * exactly, so no technician in range is missed the way an approximate index searched first could miss them.
 */
@Component
class HybridRanker {

    static final String JOB_FEATURE = "work-order-embedding";

    /** The semantic part for a technician without a vector, when the job has one: the middle of the pool's 0-1 scale. */
    static final double UNKNOWN_SEMANTIC = 0.5;

    /** One ranked technician with every part of the score, for the explain endpoint and logs. */
    record Match(
            UUID technicianId,
            String fullName,
            double distanceKm,
            List<String> skills,
            double total,
            @Nullable Double semantic,
            double skillCoverage,
            double distance,
            double rating,
            double reliability) {}

    private final TechnicianSearch technicians;
    private final EmbeddingStore store;
    private final Embeddings embeddings;
    private final MatchingProperties matching;

    HybridRanker(
            TechnicianSearch technicians, EmbeddingStore store, Embeddings embeddings, MatchingProperties matching) {
        this.technicians = technicians;
        this.store = store;
        this.embeddings = embeddings;
        this.matching = matching;
    }

    /**
     * @param jobVector the job text's vector, or empty when it couldn't be embedded: ranking then goes on without
     *     the semantic part rather than failing
     */
    List<Match> rank(GeoPoint site, int radiusKm, List<String> requiredSkills, Optional<float[]> jobVector, int limit) {
        var pool = technicians.nearestWithAnySkills(site, radiusKm, matching.poolSize());
        var ids = pool.stream().map(NearbyTechnician::id).toList();
        Map<UUID, float[]> vectors =
                jobVector.isPresent() ? store.technicianVectors(ids, embeddings.model()) : Map.of();
        var completion = store.completion(ids);

        // Cosine similarity of text vectors sits in a narrow band (about 0.5-0.8): scaling it across the pool
        // makes "the most relevant here" 1 and "the least" 0, comparable with the other components.
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        var similarity = new java.util.HashMap<UUID, Double>();
        if (jobVector.isPresent()) {
            for (var id : ids) {
                var vector = vectors.get(id);
                if (vector != null) {
                    double s = cosine(jobVector.get(), vector);
                    similarity.put(id, s);
                    lowest = Math.min(lowest, s);
                    highest = Math.max(highest, s);
                }
            }
        }

        var required = new HashSet<>(requiredSkills);
        var w = matching.weights();
        double range = highest - lowest;
        var finalLowest = lowest;
        return pool.stream()
                .map(t -> {
                    double coverage = required.isEmpty()
                            ? 1
                            : t.skills().stream().filter(required::contains).count() / (double) required.size();
                    var raw = similarity.get(t.id());
                    Double semantic = raw == null ? null : range > 0 ? (raw - finalLowest) / range : 1.0;
                    double distance = Math.max(0, 1 - t.distanceKm() / radiusKm);
                    double rating = rating(t.rating());
                    var done = completion.getOrDefault(t.id(), new EmbeddingStore.Completion(0, 0));
                    double reliability = (done.completed() + 1.0) / (done.assigned() + 2.0);

                    double weighted = w.skills() * coverage
                            + w.distance() * distance
                            + w.rating() * rating
                            + w.reliability() * reliability;
                    double weights = w.skills() + w.distance() + w.rating() + w.reliability();
                    if (jobVector.isPresent()) {
                        // A technician not embedded yet gets the pool's midpoint, not a pass: dropping the part
                        // for them alone would rank "unknown" above "measured and mediocre".
                        weighted += w.semantic() * (semantic == null ? UNKNOWN_SEMANTIC : semantic);
                        weights += w.semantic();
                    }
                    return new Match(
                            t.id(),
                            t.fullName(),
                            t.distanceKm(),
                            t.skills(),
                            weighted / weights,
                            semantic,
                            coverage,
                            distance,
                            rating,
                            reliability);
                })
                .filter(m -> m.skillCoverage() >= matching.minSkillCoverage())
                .sorted(Comparator.comparingDouble(Match::total).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * The job text's vector: stored after the first time, so routing and every explain share one embedding call.
     * Call as the work order's tenant (the table has row-level security).
     */
    Optional<float[]> jobVector(UUID workOrderId, UUID tenantId, String title, String description) {
        var text = title + ". " + description;
        var hash = EmbeddingStore.hash(text);
        var stored = store.workOrderVector(workOrderId, embeddings.model(), hash);
        if (stored.isPresent()) {
            return stored;
        }
        var embedded = embeddings
                .embed(JOB_FEATURE, Embeddings.Kind.QUERY, List.of(text))
                .map(List::getFirst);
        embedded.ifPresent(vector -> store.saveWorkOrder(workOrderId, tenantId, vector, embeddings.model(), hash));
        return embedded;
    }

    private static double rating(@Nullable BigDecimal rating) {
        return rating == null ? 0.6 : rating.doubleValue() / 5;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.technician.NearbyTechnician;
import dev.rashed.dispatch.technician.TechnicianSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The matching eval (step 3.4, T30): 30 jobs and 125 technicians around one site, where tags alone can't pick
 * the right person. Several technicians share each tag ("printer"), but their bios name different specialties
 * (Canon copiers, HP LaserJets, receipt printers), and generalists who carry the tag but mostly do something else
 * live closest. Each job's relevant technicians are the five specialists of its specialty.
 *
 * <p>Reports precision@5 (relevant technicians among the five offered) for tags only, hybrid, and hybrid without
 * or with only the semantic part. Run on purpose, like the intake eval:
 *
 * <pre>
 * export $(grep ^GEMINI_API_KEY= ../.env)
 * ./mvnw test -Dtest=MatchingEvals -DexcludedGroups=none
 * </pre>
 *
 * Embeddings are recorded in {@code evals/matching/recordings}; re-runs replay them.
 */
@Tag("eval")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "dispatch.ai.replay.mode=record",
            "dispatch.ai.replay.directory=../evals/matching/recordings",
        })
class MatchingEvals {

    static final int K = 5;
    static final int RADIUS_KM = 20;

    record Technician(
            String name,
            List<String> skills,
            String bio,
            String specialty,
            double distanceKm,
            int bearing,
            double rating) {}

    record Job(
            String id,
            String title,
            String description,
            List<String> skills,
            String specialty,
            List<String> relevant) {}

    record Cases(GeoPoint site, List<Technician> technicians, List<Job> jobs) {}

    @Autowired
    TechnicianSearch search;

    @Autowired
    EmbeddingStore store;

    @Autowired
    Embeddings embeddings;

    @Autowired
    TechnicianEmbedder embedder;

    @Autowired
    MatchingProperties matching;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Test
    void matching() throws Exception {
        var cases = json.readValue(Files.readString(Path.of("../evals/matching/cases.json")), Cases.class);
        var names = new HashMap<UUID, String>();
        int phone = 0;
        for (var t : cases.technicians()) {
            var id = UUID.randomUUID();
            names.put(id, t.name());
            double lat = cases.site().latitude() + t.distanceKm() * Math.cos(Math.toRadians(t.bearing())) / 111.0;
            double lon = cases.site().longitude()
                    + t.distanceKm() * Math.sin(Math.toRadians(t.bearing())) / (111.0 * Math.cos(Math.toRadians(lat)));
            jdbc.update(
                    """
                    insert into technician (id, full_name, phone, skills, bio, home_latitude, home_longitude,
                        service_radius_km, rating, status)
                    values (?, ?, ?, ?, ?, ?, ?, 30, ?, 'VERIFIED')
                    """,
                    id,
                    t.name(),
                    "+88019" + String.format("%08d", ++phone),
                    t.skills().toArray(String[]::new),
                    t.bio(),
                    lat,
                    lon,
                    t.rating());
        }

        // Profiles in batches under the free tier's 100 texts a minute (each text counts); recorded ones are free.
        var ids = new ArrayList<>(names.keySet());
        for (int from = 0; from < ids.size(); from += 60) {
            long started = System.nanoTime();
            if (embedder.embed(ids.subList(from, Math.min(from + 60, ids.size()))) < 0) {
                throw new IllegalStateException("Couldn't embed technician profiles; is GEMINI_API_KEY exported?");
            }
            if (System.nanoTime() - started > 1_000_000_000L) Thread.sleep(61_000); // a real call: wait the minute out
        }
        var jobTexts = cases.jobs().stream()
                .map(j -> j.title() + ". " + j.description())
                .toList();
        var jobVectors = embeddings
                .embed(HybridRanker.JOB_FEATURE, Embeddings.Kind.QUERY, jobTexts)
                .orElseThrow(() -> new IllegalStateException("Couldn't embed the jobs"));

        var w = matching.weights();
        var strategies = new LinkedHashMap<String, Ranking>();
        strategies.put(
                "Tags only (Phase 1)",
                (job, vector) -> search.nearest(cases.site(), RADIUS_KM, job.skills(), K).stream()
                        .map(NearbyTechnician::id)
                        .toList());
        strategies.put("Hybrid", ranker(cases.site(), w, vector -> vector));
        strategies.put("Hybrid without semantic", ranker(cases.site(), w, vector -> Optional.empty()));
        strategies.put(
                "Semantic only", ranker(cases.site(), new MatchingProperties.Weights(1, 0, 0, 0, 0), vector -> vector));
        // Weight variants: free to compare once the embeddings are recorded.
        for (var variant : List.of(
                new MatchingProperties.Weights(0.5, 0.2, 0.15, 0.1, 0.05),
                new MatchingProperties.Weights(0.6, 0.15, 0.1, 0.1, 0.05))) {
            strategies.put(
                    "Hybrid %s/%s/%s/%s/%s"
                            .formatted(
                                    variant.semantic(),
                                    variant.skills(),
                                    variant.distance(),
                                    variant.rating(),
                                    variant.reliability()),
                    ranker(cases.site(), variant, vector -> vector));
        }

        var out = new StringBuilder("# Matching eval\n\n");
        out.append("Run ")
                .append(ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" (Asia/Dhaka). ")
                .append(cases.jobs().size())
                .append(" jobs, ")
                .append(cases.technicians().size())
                .append(" technicians. Weights: semantic ")
                .append(w.semantic())
                .append(", skills ")
                .append(w.skills())
                .append(", distance ")
                .append(w.distance())
                .append(", rating ")
                .append(w.rating())
                .append(", reliability ")
                .append(w.reliability())
                .append(".\n\n| Ranking | Precision@5 |\n|---|---|\n");
        var perJob = new StringBuilder("\n## Per job (relevant among the 5 offered)\n\n| Job |");
        strategies.keySet().forEach(name -> perJob.append(' ').append(name).append(" |"));
        perJob.append("\n|---|").append("---|".repeat(strategies.size())).append('\n');
        var totals = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < cases.jobs().size(); i++) {
            var job = cases.jobs().get(i);
            var relevant = new HashSet<>(job.relevant());
            var vector = Optional.of(jobVectors.get(i));
            perJob.append("| ").append(job.id()).append(" |");
            for (var entry : strategies.entrySet()) {
                var offered = entry.getValue().rank(job, vector);
                int hits = (int) offered.stream()
                        .filter(id -> relevant.contains(names.get(id)))
                        .count();
                totals.merge(entry.getKey(), hits, Integer::sum);
                perJob.append(' ').append(hits).append(" |");
            }
            perJob.append('\n');
        }
        totals.forEach((name, hits) -> out.append("| ")
                .append(name)
                .append(" | ")
                .append("%.2f".formatted(hits / (double) (K * cases.jobs().size())))
                .append(" |\n"));
        out.append(perJob);

        System.out.println(out);
        Files.writeString(Path.of("../evals/results/matching.md"), out);
    }

    interface Ranking {
        List<UUID> rank(Job job, Optional<float[]> vector);
    }

    interface VectorChoice {
        Optional<float[]> apply(Optional<float[]> vector);
    }

    private Ranking ranker(GeoPoint site, MatchingProperties.Weights weights, VectorChoice choice) {
        var properties = new MatchingProperties(
                MatchingProperties.Strategy.HYBRID, matching.poolSize(), matching.minSkillCoverage(), weights);
        var ranker = new HybridRanker(search, store, embeddings, properties);
        return (job, vector) -> ranker.rank(site, RADIUS_KM, job.skills(), choice.apply(vector), K).stream()
                .map(HybridRanker.Match::technicianId)
                .toList();
    }
}

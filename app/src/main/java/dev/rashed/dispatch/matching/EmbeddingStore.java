package dev.rashed.dispatch.matching;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matching's vectors (V16): one per technician profile and per routed work order, each with the model that made
 * it and a hash of the text it was made from. Vectors go over JDBC as pgvector's text form, "[0.1,0.2,...]".
 */
@Component
class EmbeddingStore {

    /** How many of a technician's assigned jobs were completed, across all companies. */
    record Completion(int assigned, int completed) {}

    private final JdbcClient jdbc;

    EmbeddingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Map<UUID, float[]> technicianVectors(Collection<UUID> ids, String model) {
        var vectors = new HashMap<UUID, float[]>();
        if (ids.isEmpty()) {
            return vectors;
        }
        jdbc.sql("""
                        SELECT technician_id, embedding::text AS vector FROM technician_embedding
                        WHERE technician_id = ANY(:ids) AND model = :model
                        """)
                .param("ids", ids.toArray(UUID[]::new))
                .param("model", model)
                .query((rs, n) -> vectors.put(rs.getObject("technician_id", UUID.class), parse(rs.getString("vector"))))
                .list();
        return vectors;
    }

    /** The text hash each technician was last embedded from with this model; absent means "needs embedding". */
    @Transactional(readOnly = true)
    Map<UUID, String> technicianHashes(Collection<UUID> ids, String model) {
        var hashes = new HashMap<UUID, String>();
        if (ids.isEmpty()) {
            return hashes;
        }
        jdbc.sql(
                        "SELECT technician_id, source_hash FROM technician_embedding WHERE technician_id = ANY(:ids) AND model = :model")
                .param("ids", ids.toArray(UUID[]::new))
                .param("model", model)
                .query((rs, n) -> hashes.put(rs.getObject("technician_id", UUID.class), rs.getString("source_hash")))
                .list();
        return hashes;
    }

    @Transactional
    void saveTechnician(UUID technicianId, float[] vector, String model, String sourceHash) {
        jdbc.sql("""
                        INSERT INTO technician_embedding (technician_id, embedding, model, source_hash)
                        VALUES (:id, CAST(:vector AS vector), :model, :hash)
                        ON CONFLICT (technician_id) DO UPDATE
                        SET embedding = EXCLUDED.embedding, model = EXCLUDED.model, source_hash = EXCLUDED.source_hash,
                            embedded_at = now()
                        """)
                .param("id", technicianId)
                .param("vector", format(vector))
                .param("model", model)
                .param("hash", sourceHash)
                .update();
    }

    /** A vector of text that has since changed is wrong; without one, ranking just skips the semantic part. */
    @Transactional
    void deleteTechnician(UUID technicianId) {
        jdbc.sql("DELETE FROM technician_embedding WHERE technician_id = :id")
                .param("id", technicianId)
                .update();
    }

    /** Under row-level security: call as the work order's tenant. */
    @Transactional(readOnly = true)
    Optional<float[]> workOrderVector(UUID workOrderId, String model, String sourceHash) {
        return jdbc.sql("""
                        SELECT embedding::text FROM work_order_embedding
                        WHERE work_order_id = :id AND model = :model AND source_hash = :hash
                        """)
                .param("id", workOrderId)
                .param("model", model)
                .param("hash", sourceHash)
                .query(String.class)
                .optional()
                .map(EmbeddingStore::parse);
    }

    /** Under row-level security: call as the work order's tenant. */
    @Transactional
    void saveWorkOrder(UUID workOrderId, UUID tenantId, float[] vector, String model, String sourceHash) {
        jdbc.sql("""
                        INSERT INTO work_order_embedding (work_order_id, tenant_id, embedding, model, source_hash)
                        VALUES (:id, :tenant, CAST(:vector AS vector), :model, :hash)
                        ON CONFLICT (work_order_id) DO UPDATE
                        SET embedding = EXCLUDED.embedding, model = EXCLUDED.model, source_hash = EXCLUDED.source_hash,
                            embedded_at = now()
                        """)
                .param("id", workOrderId)
                .param("tenant", tenantId)
                .param("vector", format(vector))
                .param("model", model)
                .param("hash", sourceHash)
                .update();
    }

    /** Counts only, through the SECURITY DEFINER function in V16 (other companies' work orders stay hidden). */
    @Transactional(readOnly = true)
    Map<UUID, Completion> completion(Collection<UUID> technicianIds) {
        var completion = new HashMap<UUID, Completion>();
        if (technicianIds.isEmpty()) {
            return completion;
        }
        jdbc.sql("SELECT technician_id, assigned, completed FROM technician_completion(:ids)")
                .param("ids", technicianIds.toArray(UUID[]::new))
                .query((rs, n) -> completion.put(
                        rs.getObject("technician_id", UUID.class),
                        new Completion(rs.getInt("assigned"), rs.getInt("completed"))))
                .list();
        return completion;
    }

    static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String format(float[] vector) {
        var text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) text.append(',');
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    static float[] parse(String text) {
        var parts = text.substring(1, text.length() - 1).split(",");
        var vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}

package dev.rashed.dispatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rashed.dispatch.KafkaTestcontainersConfiguration;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.outbox.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A profile change travels outbox -> Kafka -> embedder -> vector, with a scripted embedding model. The embedder
 * is idempotent by text hash, and a provider outage removes the stale vector instead of keeping a wrong one.
 */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class TechnicianEmbeddingTests {

    static final String MODEL = "test-embedding";
    static final String BIO = "10 years with Canon photocopiers";

    @MockitoBean
    Embeddings embeddings;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmbeddingStore store;

    @Autowired
    EmbeddingBackfill backfill;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    UUID technician;

    @BeforeEach
    void setUp() {
        given(embeddings.model()).willReturn(MODEL);
        given(embeddings.embed(eq(TechnicianEmbedder.FEATURE), eq(Embeddings.Kind.DOCUMENT), anyList()))
                .willAnswer(call -> Optional.of(((List<?>) call.getArgument(2))
                        .stream().map(t -> HybridRankerTests.axis(3)).toList()));
        technician = TestTechnicians.verified(jdbc, 23.8, 90.4, "printer");
        var container = listeners.getListenerContainer(TechnicianEmbedder.CONSUMER);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> container.getAssignedPartitions() != null
                        && !container.getAssignedPartitions().isEmpty());
    }

    @Test
    void aProfileUpdateIsEmbeddedThroughKafka() throws Exception {
        update(BIO);

        var expectedHash = EmbeddingStore.hash("Skills: printer. About: " + BIO);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> expectedHash.equals(
                        store.technicianHashes(List.of(technician), MODEL).get(technician)));
        assertThat(store.technicianVectors(List.of(technician), MODEL).get(technician)[3])
                .isEqualTo(1.0f);
    }

    @Test
    void theSameEventTwiceEmbedsOnce() throws Exception {
        update(BIO);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> store.technicianHashes(List.of(technician), MODEL).containsKey(technician));
        clearInvocations(embeddings);

        // A redelivery (consumer restart, rebalance): the text hasn't changed, so nothing to do.
        var event = "{\"type\":\"technician.profile-changed\",\"payload\":{\"technicianId\":\"" + technician + "\"}}";
        kafka.send(Topics.TECHNICIAN_EVENTS, technician.toString(), event).join();

        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(embeddings, never()).embed(any(), any(), anyList()));
    }

    @Test
    void whenTheProviderIsDownTheStaleVectorIsRemovedAndTheBackfillRedoesIt() throws Exception {
        update(BIO);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> store.technicianHashes(List.of(technician), MODEL).containsKey(technician));

        given(embeddings.embed(eq(TechnicianEmbedder.FEATURE), eq(Embeddings.Kind.DOCUMENT), anyList()))
                .willReturn(Optional.empty());
        update("Now mostly CCTV for bank vaults");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> store.technicianHashes(List.of(technician), MODEL).isEmpty());

        // The provider is back; the backfill finds the technician without a vector and embeds the new text.
        given(embeddings.embed(eq(TechnicianEmbedder.FEATURE), eq(Embeddings.Kind.DOCUMENT), anyList()))
                .willAnswer(call -> Optional.of(((List<?>) call.getArgument(2))
                        .stream().map(t -> HybridRankerTests.axis(4)).toList()));
        for (int round = 0;
                round < 1000
                        && store.technicianHashes(List.of(technician), MODEL).isEmpty();
                round++) {
            backfill.run();
        }
        assertThat(store.technicianHashes(List.of(technician), MODEL))
                .isEqualTo(Map.of(
                        technician, EmbeddingStore.hash("Skills: printer. About: Now mostly CCTV for bank vaults")));
    }

    private void update(String bio) throws Exception {
        mvc.perform(put("/api/technicians/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTechnicians.token(technician))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": [\"printer\"], \"serviceRadiusKm\": 20, \"bio\": \"" + bio + "\"}"))
                .andExpect(status().isOk());
    }
}

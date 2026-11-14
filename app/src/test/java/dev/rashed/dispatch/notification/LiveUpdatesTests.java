package dev.rashed.dispatch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rashed.dispatch.KafkaTestcontainersConfiguration;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.outbox.OutboxEnvelope;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A dispatcher's open dashboard receives its own company's changes as they happen, and nobody else's. */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdatesTests {

    @LocalServerPort
    int port;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    final HttpClient http = HttpClient.newHttpClient();
    final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader;

    @BeforeEach
    void theLiveListenerIsReading() {
        // It starts at the latest offset, so anything sent before it has its partitions would be missed.
        var container = listeners.getListenerContainer("live-updates");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> container.getAssignedPartitions() != null
                        && container.getAssignedPartitions().size() == 6);
    }

    @AfterEach
    void closeStream() {
        if (reader != null) {
            reader.interrupt();
        }
    }

    @Test
    void aDispatcherSeesTheirCompanysChangesAndNoOtherCompanys() throws Exception {
        var response = open(TestTokens.dispatcher());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(next()).isEqualTo(":connected"); // the stream is open before any event

        var otherCompany = UUID.randomUUID();
        var otherEvent = UUID.randomUUID();
        send(otherCompany, otherEvent, UUID.randomUUID());
        var ownEvent = UUID.randomUUID();
        var workOrder = UUID.randomUUID();
        send(TestTokens.MEGHNA_BANK, ownEvent, workOrder);

        var event = readEvent();
        assertThat(event)
                .contains("id:" + ownEvent)
                .contains("event:" + WorkOrderStatusChangedV1.TYPE)
                .anySatisfy(line -> assertThat(line)
                        .startsWith("data:")
                        .contains("\"workOrderId\":\"" + workOrder + "\"")
                        .contains("\"to\":\"ASSIGNED\""));
        assertThat(String.join("\n", event)).doesNotContain(otherEvent.toString());
    }

    @Test
    void techniciansCantOpenTheDashboardStream() throws Exception {
        var technician = TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN"))
                .claim("technician_id", UUID.randomUUID().toString()));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/live/work-orders"))
                .header("Authorization", "Bearer " + technician)
                .header("Accept", "text/event-stream")
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(403);
    }

    private HttpResponse<java.io.InputStream> open(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/live/work-orders"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        reader = Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    lines.add(line);
                }
            } catch (Exception closed) {
                // the test is over
            }
        });
        return response;
    }

    private String next() throws InterruptedException {
        var line = lines.poll(10, TimeUnit.SECONDS);
        assertThat(line).as("a line from the stream").isNotNull();
        return line;
    }

    /** The next event with data: its lines up to the blank line that ends it, skipping comments. */
    private List<String> readEvent() throws InterruptedException {
        var event = new java.util.ArrayList<String>();
        while (true) {
            var line = next();
            if (line.startsWith(":")) {
                continue;
            }
            if (line.isEmpty()) {
                if (event.stream().anyMatch(field -> field.startsWith("data:"))) {
                    return event;
                }
                event.clear(); // e.g. the opening block, which only sets retry:
                continue;
            }
            event.add(line);
        }
    }

    private void send(UUID tenant, UUID eventId, UUID workOrder) {
        var envelope = new OutboxEnvelope(
                eventId,
                WorkOrderStatusChangedV1.TYPE,
                WorkOrderStatusChangedV1.VERSION,
                Instant.now(),
                tenant,
                workOrder.toString(),
                new WorkOrderStatusChangedV1(
                        workOrder,
                        "Router down",
                        WorkOrderStatus.ROUTED,
                        WorkOrderStatus.ASSIGNED,
                        null,
                        Instant.now()));
        kafka.send(new ProducerRecord<>(
                        Topics.WORK_ORDER_EVENTS, workOrder.toString(), json.writeValueAsString(envelope)))
                .join();
    }
}

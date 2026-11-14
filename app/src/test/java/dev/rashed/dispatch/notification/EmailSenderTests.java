package dev.rashed.dispatch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rashed.dispatch.TestcontainersConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Queued emails reach a real mail server (Mailpit), and survive one that's down without slowing anything. */
@Import({TestcontainersConfiguration.class, EmailSenderTests.Mailpit.class})
@SpringBootTest
class EmailSenderTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Mailpit {

        @Bean
        GenericContainer<?> mailpitContainer() {
            return new GenericContainer<>("axllent/mailpit:v1.33.1")
                    .withExposedPorts(1025, 8025)
                    .waitingFor(Wait.forHttp("/readyz").forPort(8025));
        }

        @Bean
        DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
            return registry -> {
                registry.add("spring.mail.host", mailpitContainer::getHost);
                registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(1025));
                registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "1000");
                registry.add("spring.mail.properties.mail.smtp.timeout", () -> "1000");
                registry.add("dispatch.mail.sender-enabled", () -> "true");
                registry.add("dispatch.mail.send-interval", () -> "PT0.2S");
                registry.add("dispatch.mail.first-retry-after", () -> "PT1S");
                registry.add("dispatch.mail.circuit.open-for", () -> "PT3S");
            };
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    EmailOutbox emails;

    @Autowired
    EmailSender sender;

    @Autowired
    CircuitBreaker smtpCircuitBreaker;

    @Autowired
    GenericContainer<?> mailpitContainer;

    RestClient mailpit;
    final String recipient = "dispatch-" + UUID.randomUUID() + "@bank.example";

    @BeforeEach
    void setUp() {
        mailpit =
                RestClient.create("http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(8025));
    }

    @AfterEach
    void mailServerBack() {
        if (mailpitContainer
                .getDockerClient()
                .inspectContainerCmd(mailpitContainer.getContainerId())
                .exec()
                .getState()
                .getPaused()) {
            mailpitContainer
                    .getDockerClient()
                    .unpauseContainerCmd(mailpitContainer.getContainerId())
                    .exec();
        }
    }

    @Test
    void aQueuedEmailArrivesWithBothAnHtmlAndATextPart() {
        queue("Ready for your approval: CCTV camera offline");

        var message = await().atMost(Duration.ofSeconds(10))
                .until(() -> inbox(recipient), list -> list.size() == 1)
                .getFirst();
        assertThat(message.get("Subject")).isEqualTo("Ready for your approval: CCTV camera offline");
        var full = mailpit.get()
                .uri("/api/v1/message/{id}", message.get("ID"))
                .retrieve()
                .body(MAP);
        assertThat((String) full.get("HTML")).contains("<h1");
        assertThat((String) full.get("Text")).contains("plain text body");
        assertThat(sentAt()).isNotNull();
    }

    /**
     * Mail server down: the circuit opens after a few failures, and from then on a send round returns in
     * milliseconds without touching the network. Nothing is lost: every email arrives once the server is back.
     */
    @Test
    void whileTheMailServerIsDownTheCircuitOpensAndEveryEmailArrivesLater() {
        smtpCircuitBreaker.reset();
        mailpitContainer
                .getDockerClient()
                .pauseContainerCmd(mailpitContainer.getContainerId())
                .exec();
        for (int i = 0; i < 6; i++) {
            queue("Email " + i);
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> smtpCircuitBreaker.getState() == CircuitBreaker.State.OPEN);
        var started = System.nanoTime();
        sender.sendBatch(); // what every scheduled round does while the circuit is open
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
        assertThat(pending()).isEqualTo(6); // still queued, none given up
        assertThat(untried()).isGreaterThan(0); // the open circuit spared the rest a pointless try

        mailpitContainer
                .getDockerClient()
                .unpauseContainerCmd(mailpitContainer.getContainerId())
                .exec();

        await().atMost(Duration.ofSeconds(30)).until(() -> inbox(recipient).size() == 6);
        assertThat(pending()).isZero();
        await().atMost(Duration.ofSeconds(5)).until(() -> smtpCircuitBreaker.getState() == CircuitBreaker.State.CLOSED);
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private void queue(String subject) {
        transaction.executeWithoutResult(status -> emails.queue(
                UUID.randomUUID(),
                new EmailOutbox.Email(recipient, subject, "plain text body", "<h1>" + subject + "</h1>")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> inbox(String to) {
        var result = mailpit.get()
                .uri(b -> b.path("/api/v1/search")
                        .queryParam("query", "to:\"" + to + "\"")
                        .build())
                .retrieve()
                .body(MAP);
        return (List<Map<String, Object>>) result.get("messages");
    }

    private Object sentAt() {
        return jdbc.sql("select max(sent_at) from outgoing_email where recipient = ?")
                .param(recipient)
                .query(Object.class)
                .single();
    }

    private int pending() {
        return jdbc.sql(
                        "select count(*) from outgoing_email where recipient = ? and sent_at is null and failed_at is null")
                .param(recipient)
                .query(Integer.class)
                .single();
    }

    private int untried() {
        return jdbc.sql("select count(*) from outgoing_email where recipient = ? and attempts = 0")
                .param(recipient)
                .query(Integer.class)
                .single();
    }
}

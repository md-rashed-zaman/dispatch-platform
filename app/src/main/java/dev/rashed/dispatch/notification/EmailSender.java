package dev.rashed.dispatch.notification;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers queued emails (T20). Two layers of retry:
 *
 * <ul>
 *   <li>Short: one quick retry for a blip (Resilience4j), behind a circuit breaker that stops calling a mail
 *       server that keeps failing, so a dead server costs microseconds per email instead of a timeout.
 *   <li>Long: a failed email stays in the table and is tried again after 30 s, 1, 2, 4 min ... up to an hour,
 *       ten attempts in all (about three hours), then it's marked failed and logged.
 * </ul>
 */
@Component
class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final TransactionTemplate transaction;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String from;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration firstRetryAfter;
    private final boolean enabled;

    // The switch is checked at run time, like the outbox relay, so a test can turn it on.
    EmailSender(
            JdbcClient jdbc,
            JavaMailSender mail,
            PlatformTransactionManager transactionManager,
            CircuitBreaker smtpCircuitBreaker,
            Retry smtpRetry,
            @Value("${dispatch.mail.from}") String from,
            @Value("${dispatch.mail.batch-size:20}") int batchSize,
            @Value("${dispatch.mail.max-attempts:10}") int maxAttempts,
            @Value("${dispatch.mail.first-retry-after:PT30S}") Duration firstRetryAfter,
            @Value("${dispatch.mail.sender-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.transaction = new TransactionTemplate(transactionManager);
        this.circuitBreaker = smtpCircuitBreaker;
        this.retry = smtpRetry;
        this.from = from;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.firstRetryAfter = firstRetryAfter;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${dispatch.mail.send-interval:PT2S}")
    void run() {
        if (!enabled) {
            return;
        }
        try {
            while (sendBatch()) {
                // a backlog drains at full speed while the mail server keeps up
            }
        } catch (RuntimeException e) {
            log.warn("Email sender failed, will retry", e); // the database, not the mail server
        }
    }

    /**
     * Emails are independent, so several instances may send at once: SKIP LOCKED gives each its own rows. The
     * row locks are held while sending; the circuit breaker and the SMTP timeouts keep that short.
     *
     * @return whether to go on right away: a full batch was sent, and the mail server is answering
     */
    boolean sendBatch() {
        return Objects.requireNonNull(transaction.execute(status -> {
            var due = jdbc.sql("""
                            select id, message_id, recipient, subject, text_body, html_body, attempts
                            from outgoing_email
                            where sent_at is null and failed_at is null and next_attempt_at <= now()
                            order by id limit :limit
                            for update skip locked
                            """)
                    .param("limit", batchSize)
                    .query((rs, rowNum) -> new Queued(
                            rs.getLong("id"),
                            rs.getObject("message_id", UUID.class),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("text_body"),
                            rs.getString("html_body"),
                            rs.getInt("attempts")))
                    .list();
            for (var email : due) {
                try {
                    Retry.decorateRunnable(retry, CircuitBreaker.decorateRunnable(circuitBreaker, () -> deliver(email)))
                            .run();
                    jdbc.sql("update outgoing_email set sent_at = now(), attempts = attempts + 1 where id = :id")
                            .param("id", email.id())
                            .update();
                } catch (CallNotPermittedException open) {
                    // The circuit is open: don't count it as an attempt, and leave the rest of the batch due.
                    return false;
                } catch (RuntimeException e) {
                    recordFailure(email, e);
                }
            }
            return due.size() == batchSize;
        }));
    }

    private void deliver(Queued email) {
        mail.send(message -> {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email.recipient());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html()); // clients without HTML show the text part
            message.setHeader("X-Dispatch-Message-Id", email.messageId().toString());
        });
    }

    private void recordFailure(Queued email, RuntimeException e) {
        var attempts = email.attempts() + 1;
        var reason = NestedExceptionUtils.getMostSpecificCause(e).toString();
        if (attempts >= maxAttempts) {
            jdbc.sql("""
                            update outgoing_email set attempts = :attempts, last_error = :reason, failed_at = now()
                            where id = :id
                            """)
                    .param("attempts", attempts)
                    .param("reason", reason)
                    .param("id", email.id())
                    .update();
            log.error(
                    "Gave up on email {} to {} after {} attempts: {}", email.id(), email.recipient(), attempts, reason);
            return;
        }
        var doubled = firstRetryAfter.multipliedBy(1L << Math.min(attempts - 1, 16)); // 30 s, 1, 2, 4 min ...
        var wait = doubled.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : doubled;
        jdbc.sql("""
                        update outgoing_email
                        set attempts = :attempts, last_error = :reason, next_attempt_at = now() + :wait * interval '1 millisecond'
                        where id = :id
                        """)
                .param("attempts", attempts)
                .param("reason", reason)
                .param("wait", wait.toMillis())
                .param("id", email.id())
                .update();
        log.warn(
                "Email {} failed (attempt {} of {}), next try in {}: {}",
                email.id(),
                attempts,
                maxAttempts,
                wait,
                reason);
    }

    private record Queued(
            long id, UUID messageId, String recipient, String subject, String text, String html, int attempts) {}
}

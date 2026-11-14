package dev.rashed.dispatch.notification;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/** The circuit breaker and retry around the mail server (T20). Configured in code, without the starter. */
@Configuration
class MailResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(MailResilienceConfig.class);

    /**
     * Closed: calls go through, and the last 10 are watched. When at least half of them fail (and there have
     * been at least 4), it opens: calls fail at once, without touching the network. After the wait it's
     * half-open: one trial call decides whether it closes again or stays open for another wait.
     */
    @Bean
    CircuitBreaker smtpCircuitBreaker(@Value("${dispatch.mail.circuit.open-for:PT30S}") Duration openFor) {
        var breaker = CircuitBreaker.of(
                "smtp",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(openFor)
                        .permittedNumberOfCallsInHalfOpenState(1)
                        // A message we built badly is our bug, not a sign the server is down.
                        .recordExceptions(MailException.class)
                        .ignoreExceptions(MailParseException.class, MailPreparationException.class)
                        .build());
        breaker.getEventPublisher()
                .onStateTransition(event -> log.warn("SMTP circuit breaker: {}", event.getStateTransition()));
        return breaker;
    }

    /** One quick second try, for a connection that dropped once. Longer outages are the table's job. */
    @Bean
    Retry smtpRetry() {
        return Retry.of(
                "smtp",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(500))
                        .retryExceptions(MailSendException.class)
                        .ignoreExceptions(CallNotPermittedException.class) // open means "don't call", so don't
                        .build());
    }
}

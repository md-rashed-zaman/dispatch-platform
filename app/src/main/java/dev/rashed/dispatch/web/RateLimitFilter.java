package dev.rashed.dispatch.web;

import dev.rashed.dispatch.identity.CurrentUser;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-caller rate limit (T24): a token bucket per company (or per technician), kept in Redis so every app
 * instance draws from the same bucket. Over the limit: 429 with Retry-After.
 *
 * <p>Redis down: requests are let through (fail open). A rate limit protects the service; taking the whole API
 * down because the limiter can't count would do the attacker's job. A circuit breaker skips Redis entirely
 * while it's down, so requests don't each wait for a timeout.
 *
 * <p>Registered after Spring Security (see {@link WebConfig}), so it knows who's calling. Unauthenticated
 * requests are rejected before they get here, except health checks, which aren't limited.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Read on first use, not at construction: servlet filters are created before the rest of the app. */
    private record Settings(boolean enabled, long capacity, BucketConfiguration bucket, RedisURI redis) {}

    private final Environment environment;
    private final JsonMapper json;
    private final CircuitBreaker circuitBreaker;
    private volatile @Nullable Settings settings;
    private volatile @Nullable RedisClient client;
    private volatile @Nullable StatefulRedisConnection<String, byte[]> connection;
    private volatile @Nullable ProxyManager<String> buckets;

    RateLimitFilter(Environment environment, JsonMapper json) {
        this.environment = environment;
        this.json = json;
        this.circuitBreaker = CircuitBreaker.of(
                "rate-limit-redis",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        // How long requests go unlimited after Redis fails, before one tries it again.
                        .waitDurationInOpenState(environment.getProperty(
                                "dispatch.rate-limit.redis-retry-after", Duration.class, Duration.ofSeconds(10)))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build());
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(
                        event -> log.warn("Rate limiter's Redis circuit breaker: {}", event.getStateTransition()));
    }

    private Settings settings() {
        var current = settings;
        if (current == null) {
            var capacity = environment.getProperty("dispatch.rate-limit.capacity", Long.class, 100L);
            var refillPerMinute = environment.getProperty("dispatch.rate-limit.refill-per-minute", Long.class, 100L);
            current = new Settings(
                    environment.getProperty("dispatch.rate-limit.enabled", Boolean.class, true),
                    capacity,
                    // Up to `capacity` requests at once (a burst), then tokens come back steadily.
                    BucketConfiguration.builder()
                            .addLimit(limit ->
                                    limit.capacity(capacity).refillGreedy(refillPerMinute, Duration.ofMinutes(1)))
                            .build(),
                    RedisURI.builder()
                            .withHost(environment.getProperty("spring.data.redis.host", "localhost"))
                            .withPort(environment.getProperty("spring.data.redis.port", Integer.class, 6379))
                            .withTimeout(Duration.ofMillis(300))
                            .build());
            settings = current;
        }
        return current;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !settings().enabled() || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var caller = CurrentUser.current().map(RateLimitFilter::key).orElse(null);
        if (caller == null) {
            chain.doFilter(request, response); // not signed in: Spring Security already answered 401
            return;
        }
        var probe = consume(caller);
        if (probe == null || probe.isConsumed()) {
            if (probe != null) {
                response.setHeader(
                        "X-RateLimit-Limit", String.valueOf(settings().capacity()));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            }
            chain.doFilter(request, response);
            return;
        }
        var retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000);
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests for this account. Try again in " + retryAfterSeconds + " s.");
        problem.setType(URI.create("urn:dispatch:problem:rate-limited"));
        problem.setTitle("Too many requests");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", String.valueOf(settings().capacity()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(problem));
    }

    /** The company for company users, so one company can't use up the API for others; else the person. */
    static String key(CurrentUser user) {
        if (user.tenantId() != null) {
            return "tenant:" + user.tenantId();
        }
        if (user.technicianId() != null) {
            return "technician:" + user.technicianId();
        }
        return "user:" + user.id();
    }

    /** @return the result, or null when Redis can't be asked (the request is let through) */
    private @Nullable ConsumptionProbe consume(String caller) {
        try {
            return circuitBreaker.executeSupplier(() -> buckets()
                    .getProxy("rate-limit:" + caller, () -> settings().bucket())
                    .tryConsumeAndReturnRemaining(1));
        } catch (CallNotPermittedException open) {
            return null;
        } catch (RuntimeException e) {
            log.warn("Rate limit not applied, Redis unavailable: {}", e.toString());
            return null;
        }
    }

    /** Connects on first use, not at startup, so the app starts (and serves) without Redis. */
    private ProxyManager<String> buckets() {
        var ready = buckets;
        if (ready != null) {
            return ready;
        }
        synchronized (this) {
            if (buckets == null) {
                client = client != null ? client : RedisClient.create(settings().redis());
                connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                buckets = Bucket4jLettuce.casBasedBuilder(connection)
                        // A bucket nobody uses is deleted once it would be full again anyway.
                        .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(1)))
                        .build();
            }
            return buckets;
        }
    }

    void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}

package dev.rashed.dispatch;

import dev.rashed.dispatch.technician.TechnicianProfile;
import dev.rashed.dispatch.technician.TechnicianProfiles;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.json.JsonMapper;

/** Redis as a shared cache (T23). */
@Configuration
@EnableCaching
class CachingConfig implements CachingConfigurer {

    /**
     * Each entry lives 10 minutes plus or minus 10% (jitter), so entries cached at the same moment don't all
     * expire, and get reloaded, at the same moment. Values are JSON of a known type, not Java serialization.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer technicianProfileCache(
            JsonMapper json, @Value("${dispatch.cache.technician-profile-ttl:PT10M}") Duration ttl) {
        return builder -> builder.enableStatistics() // hit and miss counts for /actuator/metrics (cache.gets)
                .withCacheConfiguration(
                        TechnicianProfiles.CACHE,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl((key, value) -> jittered(ttl))
                                .disableCachingNullValues()
                                .serializeValuesWith(SerializationPair.fromSerializer(
                                        new JacksonJsonRedisSerializer<>(json, TechnicianProfile.class))));
    }

    static Duration jittered(Duration ttl) {
        var spread = ttl.toMillis() / 10;
        return ttl.plusMillis(ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    /**
     * Redis down: log and carry on, so a read goes to the database instead of failing (the default handler
     * rethrows). Each cache call then waits for the Redis command timeout first.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(LogFactory.getLog(CachingConfig.class), false);
    }
}

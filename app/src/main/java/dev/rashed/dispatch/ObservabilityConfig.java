package dev.rashed.dispatch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/** Tracing across threads (T25). */
@Configuration
class ObservabilityConfig {

    /**
     * Spring Boot puts this on its task executor, so @Async listeners (routing after a publish) run in the
     * trace of the request that triggered them instead of starting a new one.
     */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}

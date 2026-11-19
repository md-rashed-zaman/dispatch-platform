package dev.rashed.dispatch.flags;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FlagsConfig {

    /**
     * The OpenFeature client the rest of the app reads flags with. OpenFeatureAPI is one global object per JVM, so
     * the provider is registered under a domain of its own (one per application context: the tests run several
     * contexts, each with its own database, in one JVM) and the client is bound to that domain.
     */
    @Bean
    Client featureFlags(FlagStore store) {
        var domain = "dispatch-" + UUID.randomUUID();
        var api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(domain, new DispatchFlagProvider(store));
        return api.getClient(domain);
    }
}

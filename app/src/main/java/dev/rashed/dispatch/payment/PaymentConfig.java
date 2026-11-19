package dev.rashed.dispatch.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    private final PaymentProperties properties;

    PaymentConfig(PaymentProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        var gateway = properties.sslcommerz();
        if (gateway.fake()) {
            log.warn("Payments use the fake SSLCommerz at /fake-sslcommerz: anyone can 'pay'. Never in production.");
        }
        log.info(
                "Payments: provider {}, funding {}",
                gateway.baseUrl(),
                properties.required() ? "required to start work" : "optional");
    }
}

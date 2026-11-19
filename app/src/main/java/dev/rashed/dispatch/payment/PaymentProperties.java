package dev.rashed.dispatch.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param required whether a technician may start only after the client's payment is captured; off for local runs
 *     without a provider
 * @param callbackBaseUrl where the provider reaches this API: its notification (IPN) and the customer's browser
 *     coming back after paying
 * @param staleAfter an INITIATED payment older than this never got a payment page (the create call failed or
 *     timed out), so a new attempt may replace it: the customer never saw its page, so nobody can pay it
 * @param reconcileAfter how long a payment may stay open before the reconciler asks the provider about it
 * @param sessionLifetime after this, an open payment with nothing paid at the provider is EXPIRED
 */
@ConfigurationProperties("dispatch.payments")
record PaymentProperties(
        boolean required,
        URI callbackBaseUrl,
        Duration staleAfter,
        Duration reconcileAfter,
        Duration sessionLifetime,
        SslCommerz sslcommerz) {

    /**
     * @param fake serve a stand-in for SSLCommerz at /fake-sslcommerz (local runs and demos without a sandbox store)
     */
    record SslCommerz(
            URI baseUrl,
            String storeId,
            String storePassword,
            Duration connectTimeout,
            Duration readTimeout,
            boolean fake) {

        @Override
        public String toString() { // never log the store password
            return "SslCommerz[baseUrl=" + baseUrl + ", storeId=" + storeId + ", fake=" + fake + "]";
        }
    }
}

package com.crm.resource.config;

import com.crm.resource.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * One RestClient.Builder per peer, each with its own connect/read timeouts (nfr-design.md 8.1)
 * and the SAME correlation-id request initializer (nfr-design.md 3.2) — so a new peer added
 * later cannot be added without the header, since the initializer lives on the shared builder
 * factory method, not per call site.
 *
 * <p>The four @Bean methods below read the peers.* properties from application.yml
 * (QUOTA_SERVICE_URL etc.) via @Value. Each bean name matches what the corresponding Client
 * constructor injects, e.g. "quotaRestClientBuilder".
 */
@Configuration
public class RestClientConfig {

    /** Shared factory: one timeout-configured, correlation-id-attaching builder per peer. */
    private RestClient.Builder peerClient(String baseUrl, Duration connect, Duration read) {
        ClientHttpRequestFactory factory = timeouts(connect, read);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInitializer(req -> {
                    String id = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (id != null) {
                        req.getHeaders().add(CorrelationIdFilter.HEADER, id);
                    }
                });
    }

    private ClientHttpRequestFactory timeouts(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }

    // One builder bean per peer. Bean names match the constructor parameter names in the clients
    // (QuotaClient, ProviderClient, BillingClient, NotificationClient). Each peer reads its own
    // base-url / connect-timeout-ms / read-timeout-ms from application.yml's peers.* section.

    @Bean
    public RestClient.Builder quotaRestClientBuilder(
            @Value("${peers.quota.base-url}") String baseUrl,
            @Value("${peers.quota.connect-timeout-ms}") long connectMs,
            @Value("${peers.quota.read-timeout-ms}") long readMs) {
        return peerClient(baseUrl, Duration.ofMillis(connectMs), Duration.ofMillis(readMs));
    }

    @Bean
    public RestClient.Builder providerRestClientBuilder(
            @Value("${peers.provider.base-url}") String baseUrl,
            @Value("${peers.provider.connect-timeout-ms}") long connectMs,
            @Value("${peers.provider.read-timeout-ms}") long readMs) {
        return peerClient(baseUrl, Duration.ofMillis(connectMs), Duration.ofMillis(readMs));
    }

    @Bean
    public RestClient.Builder billingRestClientBuilder(
            @Value("${peers.billing.base-url}") String baseUrl,
            @Value("${peers.billing.connect-timeout-ms}") long connectMs,
            @Value("${peers.billing.read-timeout-ms}") long readMs) {
        return peerClient(baseUrl, Duration.ofMillis(connectMs), Duration.ofMillis(readMs));
    }

    @Bean
    public RestClient.Builder notificationRestClientBuilder(
            @Value("${peers.notification.base-url}") String baseUrl,
            @Value("${peers.notification.connect-timeout-ms}") long connectMs,
            @Value("${peers.notification.read-timeout-ms}") long readMs) {
        return peerClient(baseUrl, Duration.ofMillis(connectMs), Duration.ofMillis(readMs));
    }
}

package com.crm.usage.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClients {
    @Bean
    RestClient quotaClient(PeerSettings settings) { return client(settings.quota()); }

    @Bean
    RestClient resourceClient(PeerSettings settings) { return client(settings.resource()); }

    @Bean
    RestClient billingClient(PeerSettings settings) { return client(settings.billing()); }

    private RestClient client(PeerSettings.Peer peer) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(peer.connectTimeoutMs())).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofMillis(peer.readTimeoutMs()));
        return RestClient.builder().baseUrl(peer.url().toString()).requestFactory(requests)
                .requestInitializer(request -> {
                    String id = MDC.get("correlationId");
                    if (id != null) request.getHeaders().set("X-Correlation-Id", id);
                }).build();
    }
}

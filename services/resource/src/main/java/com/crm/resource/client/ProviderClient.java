package com.crm.resource.client;

import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.domain.ClientToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Stubbed against WireMock in U1 (see infrastructure-design.md 8 for the mapping, including the
 * forced-503 billing stub — this client's own 503 path exercises the same retry/compensation
 * logic). Real implementation is U3 (Vinayak)'s Provider mock service.
 *
 * <p>Every attempt carries the same {@link ClientToken#of} token, which is what makes a RETRIED
 * provision call return the EXISTING instance instead of creating a second one (FR-19, NFR-27) —
 * the provider mock is idempotent on this token. Without it, a timed-out-but-successful provision
 * followed by a retry would leak an instance the orchestrator does not know about.
 *
 * <p>Retry-with-backoff-and-jitter (nfr-design.md 8) applies to timeouts, connection failures and
 * 503 only, never to a 4xx. Exhausted retries surface as PROVIDER_UNAVAILABLE, which becomes the
 * {@code failure_reason} prefix the saga writes (functional-design.md 9.5).
 */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);
    private static final String SIMULATE_HEADER = "X-Simulate-Failure";

    private final RestClient restClient;
    private final PeerRetry retry;

    public ProviderClient(@Qualifier("providerRestClientBuilder") RestClient.Builder providerRestClientBuilder,
                          PeerRetry retry) {
        this.restClient = providerRestClientBuilder.build();
        this.retry = retry;
    }

    /** POST .../provider/instances. Returns the provider instance id on success. */
    public String provision(ClientToken clientToken, String type, int cpu, int ramGb, String simulateMode) {
        Map<String, Object> body = Map.of(
                "clientToken", clientToken.value(),
                "type", type,
                "cpu", cpu,
                "ramGb", ramGb);

        ProvisionInstanceResponse response = retry.call(
                ErrorCode.PROVIDER_UNAVAILABLE, "provider instance create", true, () ->
                        restClient.post()
                                .uri("/api/v1/provider/instances")
                                .contentType(MediaType.APPLICATION_JSON)
                                .headers(headers -> {
                                    if (StringUtils.hasText(simulateMode)) {
                                        headers.add(SIMULATE_HEADER, simulateMode);   // FR-46
                                    }
                                })
                                .body(body)
                                .retrieve()
                                .body(ProvisionInstanceResponse.class));

        if (response == null || !StringUtils.hasText(response.instanceId())) {
            // A 2xx with no instance id is not a usable success: treat it as a peer failure so
            // the saga compensates rather than activating a resource with no instance.
            throw new com.crm.resource.common.error.DownstreamUnavailableException(
                    ErrorCode.PROVIDER_UNAVAILABLE, "provider returned no instanceId (200)");
        }
        log.info("provider.provisioned instanceId={} clientToken={}", response.instanceId(), clientToken.value());
        return response.instanceId();
    }

    /** DELETE .../provider/instances/{id}. Idempotent: a 404 is success, not a failure. */
    public void terminate(String instanceId) {
        retry.callVoid(ErrorCode.PROVIDER_UNAVAILABLE, "provider instance terminate", true, () ->
                restClient.delete()
                        .uri("/api/v1/provider/instances/{instanceId}", instanceId)
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (response.getStatusCode().is2xxSuccessful() || status == 404) {
                                // 404 means the instance is already gone, which is the goal.
                                log.info("provider.terminated instanceId={} status={}", instanceId, status);
                                return null;
                            }
                            if (status == 503) {
                                throw org.springframework.web.client.HttpServerErrorException.create(
                                        response.getStatusCode(), "provider unavailable", null, null, null);
                            }
                            throw new com.crm.resource.common.error.DownstreamUnavailableException(
                                    ErrorCode.PROVIDER_UNAVAILABLE,
                                    "provider terminate rejected (" + status + ")");
                        }));
    }

    /** Mirrors the provider mock's 201 body: {@code { "instanceId": "i-mock-...", "status": "ACTIVE" }}. */
    record ProvisionInstanceResponse(String instanceId, String status) {
    }
}

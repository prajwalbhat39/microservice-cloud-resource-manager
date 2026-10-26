package com.crm.resource.client;

import com.crm.resource.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Send money as a fixed-scale decimal string. Meter retries use the same resource ID. */
@Component
public class BillingClient {

    private static final Logger log = LoggerFactory.getLogger(BillingClient.class);
    private static final String SIMULATE_HEADER = "X-Simulate-Failure";
    private static final int RATE_SCALE = 4;

    private final RestClient restClient;
    private final PeerRetry retry;

    public BillingClient(@Qualifier("billingRestClientBuilder") RestClient.Builder billingRestClientBuilder,
                         PeerRetry retry) {
        this.restClient = billingRestClientBuilder.build();
        this.retry = retry;
    }

    /** POST .../billing/meters. Idempotent on resourceId. Forced 503 here drives Challenge 3C. */
    public void startMeter(String resourceId, String tenantId, int cpu, BigDecimal ratePerCpuHour, String simulateMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceId", resourceId);
        body.put("tenantId", tenantId);
        body.put("cpu", cpu);
        body.put("ratePerCpuHour", ratePerCpuHour.setScale(RATE_SCALE, java.math.RoundingMode.HALF_UP).toPlainString());

        retry.callVoid(ErrorCode.BILLING_UNAVAILABLE, "billing meter start", true, () -> {
            restClient.post()
                    .uri("/api/v1/billing/meters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (StringUtils.hasText(simulateMode)) {
                            headers.add(SIMULATE_HEADER, simulateMode);   // FR-46
                        }
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("billing.meter.started resourceId={}", resourceId);
        });
    }

    /** POST .../billing/meters/{resourceId}/stop. Idempotent. Called from compensation and termination. */
    public void stopMeter(String resourceId) {
        retry.callVoid(ErrorCode.BILLING_UNAVAILABLE, "billing meter stop", true, () ->
                restClient.post()
                        .uri("/api/v1/billing/meters/{resourceId}/stop", resourceId)
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (response.getStatusCode().is2xxSuccessful() || status == 404) {
                                // 404: there is no meter to stop, which is the desired end state.
                                log.info("billing.meter.stopped resourceId={} status={}", resourceId, status);
                                return null;
                            }
                            if (status == 503) {
                                throw org.springframework.web.client.HttpServerErrorException.create(
                                        response.getStatusCode(), "billing unavailable", null, null, null);
                            }
                            throw new com.crm.resource.common.error.DownstreamUnavailableException(
                                    ErrorCode.BILLING_UNAVAILABLE,
                                    "billing meter stop rejected (" + status + ")");
                        }));
    }
}

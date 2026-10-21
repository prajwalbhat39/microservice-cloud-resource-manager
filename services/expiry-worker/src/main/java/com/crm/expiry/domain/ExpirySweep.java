package com.crm.expiry.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class ExpirySweep {
    private static final Logger log = LoggerFactory.getLogger(ExpirySweep.class);
    private final RestClient resource;
    private final Clock clock;

    public ExpirySweep(RestClient resource, Clock clock) { this.resource = resource; this.clock = clock; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(UUID resourceId, Instant expiresAt) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidates(List<Candidate> items) { }

    public void run() {
        try (var ignored = MDC.putCloseable("correlationId", UUID.randomUUID().toString())) {
            Candidates candidates;
            try {
                candidates = resource.get().uri("/api/v1/resources/expired").retrieve().body(Candidates.class);
            } catch (RestClientException failure) {
                log.warn("expiry.fetch.failed cause={}", failure.getClass().getSimpleName());
                return;
            }
            if (candidates == null || candidates.items() == null) {
                log.warn("expiry.fetch.invalid-response");
                return;
            }
            for (Candidate candidate : candidates.items()) {
                if (candidate == null || candidate.resourceId() == null || candidate.expiresAt() == null
                        || !candidate.expiresAt().isBefore(clock.instant())) {
                    log.warn("expiry.candidate.invalid");
                    continue;
                }
                try {
                    resource.post().uri("/api/v1/resources/{id}/termination", candidate.resourceId())
                            .retrieve().toBodilessEntity();
                    log.info("expiry.termination.accepted resourceId={}", candidate.resourceId());
                } catch (HttpClientErrorException failure) {
                    log.warn("expiry.termination.rejected resourceId={} status={}",
                            candidate.resourceId(), failure.getStatusCode().value());
                } catch (RestClientException failure) {
                    log.warn("expiry.termination.failed resourceId={} cause={}",
                            candidate.resourceId(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}

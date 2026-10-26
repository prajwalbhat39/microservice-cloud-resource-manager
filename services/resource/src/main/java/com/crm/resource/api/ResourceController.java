package com.crm.resource.api;

import com.crm.resource.api.dto.ExpiredResourceListResponse;
import com.crm.resource.api.dto.ProvisionResourceRequest;
import com.crm.resource.api.dto.ResourceListResponse;
import com.crm.resource.api.dto.ResourceResponse;
import com.crm.resource.api.dto.TerminationAcceptedResponse;
import com.crm.resource.common.error.IdempotencyKeyRequiredException;
import com.crm.resource.domain.service.ProvisionOutcome;
import com.crm.resource.domain.service.ProvisioningService;
import com.crm.resource.domain.service.ResourceQueryService;
import com.crm.resource.domain.service.TerminationOutcome;
import com.crm.resource.domain.service.TerminationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * R1, R3-R6 (functional-design.md 4). R1 is the main entry point: Challenge 2 (idempotency) and
 * Challenge 3C (saga) both start here.
 */
@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {

    private static final Logger log = LoggerFactory.getLogger(ResourceController.class);
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final ProvisioningService provisioningService;
    private final TerminationService terminationService;
    private final ResourceQueryService resourceQueryService;

    public ResourceController(ProvisioningService provisioningService,
                              TerminationService terminationService,
                              ResourceQueryService resourceQueryService) {
        this.provisioningService = provisioningService;
        this.terminationService = terminationService;
        this.resourceQueryService = resourceQueryService;
    }

    @Operation(summary = "Provision a resource (main entry). Challenge 2 + Challenge 3C (FR-11 to FR-15)")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted (fresh, replayed, or in-flight)"),
            @ApiResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_REQUIRED or VALIDATION_FAILED"),
            @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND"),
            @ApiResponse(responseCode = "422", description = "QUOTA_EXCEEDED, TIER_FORBIDS_RESOURCE_TYPE, or IDEMPOTENCY_PAYLOAD_MISMATCH"),
            @ApiResponse(responseCode = "503", description = "DOWNSTREAM_UNAVAILABLE")
    })
    @PostMapping
    public ResponseEntity<?> provision(
            @RequestHeader(value = "Idempotency-Key", required = false) @jakarta.validation.constraints.Size(max = 200) String idempotencyKey,
            @RequestHeader(value = "X-Simulate-Failure", required = false) String simulateFailure,
            @Valid @RequestBody ProvisionResourceRequest request) {

        // FR-12: absent/blank key -> 400, checked HERE (not left to @RequestHeader's own
        // MissingRequestHeaderException) because a BLANK header also must be rejected, and the
        // advice's IDEMPOTENCY_KEY_REQUIRED mapping only catches "header absent entirely".
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IdempotencyKeyRequiredException();
        }

        ProvisionOutcome outcome = provisioningService.provision(idempotencyKey, request, simulateFailure);

        // A stored answer (a replay, or the business rejection just frozen on the key) is written
        // back VERBATIM: same status, same body text, so the original correlationId/timestamp
        // survive (verbatim-replay rule, nfr-design.md 2.5). The media type is derived from the
        // stored status because this path bypasses ProblemDetailsFactory.
        if (outcome.hasStoredResponse()) {
            int status = outcome.storedStatus();
            ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                    .contentType(status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
            if (outcome.isReplayed()) {
                log.info("idempotency.replay key={} status={}", idempotencyKey, status);
                response.header(REPLAYED_HEADER, "true");
            }
            if (status == 202 && outcome.reservationId() != null) {
                response.location(URI.create("/api/v1/reservations/" + outcome.reservationId()));
            }
            return response.body(outcome.storedBody());
        }

        // Fresh 202, or an in-flight duplicate (same reservationId, flagged as a replay).
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .location(URI.create("/api/v1/reservations/" + outcome.body().reservationId()));
        if (outcome.isReplayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(outcome.body());
    }

    @Operation(summary = "Read a resource (FR-17)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    })
    @GetMapping("/{resourceId}")
    public ResourceResponse get(@PathVariable UUID resourceId) {
        return resourceQueryService.get(resourceId);
    }

    @Operation(summary = "List a tenant's resources (FR-18)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    })
    @GetMapping
    public ResourceListResponse list(@RequestParam String tenantId,
                                     @RequestParam(required = false) String status) {
        return resourceQueryService.list(tenantId, status);
    }

    @Operation(summary = "Terminate a resource, idempotent (FR-20 to FR-22)")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Terminated or already terminated"),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    })
    @PostMapping("/{resourceId}/termination")
    public ResponseEntity<TerminationAcceptedResponse> terminate(@PathVariable UUID resourceId) {
        TerminationOutcome outcome = terminationService.terminate(resourceId);
        TerminationAcceptedResponse body = new TerminationAcceptedResponse(
                resourceId, outcome.resource().getStatus(), outcome.resource().getTerminatedAt(),
                outcome.alreadyTerminated());
        return ResponseEntity.accepted().body(body);
    }

    @Operation(summary = "Internal: ACTIVE resources past expires_at, for the Expiry Worker (FR-24 support)")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/expired")
    public ExpiredResourceListResponse expired() {
        var items = resourceQueryService.expired();
        return new ExpiredResourceListResponse(items, items.size());
    }
}

package com.crm.resource.domain.service;

import com.crm.resource.api.ResourceController;
import com.crm.resource.api.dto.ProvisionAcceptedResponse;
import com.crm.resource.common.error.BusinessRuleViolationException;
import com.crm.resource.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The verbatim-replay rule (nfr-design.md 2.5): a replayed response keeps its ORIGINAL
 * correlationId and timestamp and its {@code application/problem+json} content type. It is never
 * re-stamped, because AC-2.1 asks for a response <i>identical</i> to the first and the Challenge 2
 * script asserts that by comparing bodies.
 *
 * <p>Exercised at the controller, which is where the stored bytes are written back. The service is
 * stubbed because the decision itself is covered by {@link IdempotencyServiceTest}.
 */
class IdempotencyReplayTest {

    private static final String KEY = "0f8c3a7e-provision-001";

    /** The problem document frozen on the key by the FIRST caller, under correlation origin-123. */
    private static final String STORED_REJECTION = """
            {"type":"https://crm.example/errors/quota-exceeded",\
            "title":"Quota exceeded","status":422,\
            "detail":"Requested 4 CPU exceeds remaining 0 CPU for tenant tnt-std-001",\
            "instance":"/api/v1/resources","code":"QUOTA_EXCEEDED","exhausted":["CPU"],\
            "correlationId":"origin-123","timestamp":"2026-10-14T09:12:05Z"}""";

    private ProvisioningService provisioningService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        provisioningService = mock(ProvisioningService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ResourceController(
                provisioningService, mock(TerminationService.class), mock(ResourceQueryService.class))).build();
    }

    private static String body() {
        return "{\"tenantId\":\"tnt-std-001\",\"type\":\"VM\",\"cpu\":4,\"ramGb\":8}";
    }

    @Test
    void replayedRejectionKeepsProblemJsonAndOriginalCorrelationId() throws Exception {
        // Arrange: the key owns a stored 422 problem+json whose correlationId is "origin-123".
        BusinessRuleViolationException rejection = new BusinessRuleViolationException(
                ErrorCode.QUOTA_EXCEEDED, "Requested 4 CPU exceeds remaining 0 CPU for tenant tnt-std-001",
                Map.of("exhausted", java.util.List.of("CPU")));
        when(provisioningService.provision(eq(KEY), any(), any()))
                .thenReturn(ProvisionOutcome.rejected(rejection, 422, STORED_REJECTION));

        // Act: a SECOND request, under a different correlation id, reuses the same key.
        mockMvc.perform(post("/api/v1/resources")
                        .header("Idempotency-Key", KEY)
                        .header("X-Correlation-Id", "second-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                // Assert: the stored status, and problem+json derived from it (the replay path
                // bypasses ProblemDetailsFactory, so the controller must set the media type).
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // The ORIGINAL correlationId and timestamp survive: verbatim wins over 2.4.
                .andExpect(jsonPath("$.correlationId").value("origin-123"))
                .andExpect(jsonPath("$.timestamp").value("2026-10-14T09:12:05Z"))
                // Quota's extension member is still there, which is what AC-1.3 is read from.
                .andExpect(jsonPath("$.exhausted[0]").value("CPU"))
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                // Byte-for-byte identical to what the first caller received.
                .andExpect(content().string(STORED_REJECTION));
    }

    @Test
    void replayedAcceptanceCarriesTheReplayedHeaderAndPlainJson() throws Exception {
        // A stored 202 replays as application/json with Idempotency-Replayed: true, so the
        // Challenge 2 script can tell a replay from fresh processing without reading logs (AC-2.5).
        UUID reservationId = UUID.fromString("3b9a1c2d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
        UUID resourceId = UUID.fromString("e5f1a2b3-1111-4c2d-8e3f-9a0b1c2d3e4f");
        String stored = "{\"reservationId\":\"" + reservationId + "\",\"resourceId\":\"" + resourceId
                + "\",\"status\":\"PENDING\",\"statusUrl\":\"/api/v1/reservations/" + reservationId + "\"}";

        IdempotencyDecision replay = IdempotencyDecision.replay(
                completedRecord(reservationId, 202, stored));
        when(provisioningService.provision(eq(KEY), any(), any()))
                .thenReturn(ProvisionOutcome.of(replay));

        mockMvc.perform(post("/api/v1/resources")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/reservations/" + reservationId))
                // AC-2.1: the SAME resourceId and reservationId as the first response.
                .andExpect(content().string(stored));
    }

    @Test
    void freshRequestIsNotFlaggedAsReplayed() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(provisioningService.provision(eq(KEY), any(), any())).thenReturn(ProvisionOutcome.accepted(
                ProvisionAcceptedResponse.pending(reservationId, UUID.randomUUID())));

        mockMvc.perform(post("/api/v1/resources")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(header().string("Location", "/api/v1/reservations/" + reservationId));
    }

    private static com.crm.resource.persistence.entity.IdempotencyRecord completedRecord(
            UUID reservationId, int status, String body) {
        var record = new com.crm.resource.persistence.entity.IdempotencyRecord(
                KEY, "a".repeat(64), reservationId, "COMPLETED",
                java.time.Instant.parse("2026-10-15T09:12:05Z"));
        record.setResponseStatus(status);
        record.setResponseBody(body);
        return record;
    }
}

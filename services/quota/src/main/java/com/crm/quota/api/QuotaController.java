package com.crm.quota.api;

import com.crm.quota.api.dto.ReleaseQuotaResponse;
import com.crm.quota.api.dto.ReserveQuotaRequest;
import com.crm.quota.api.dto.ReserveQuotaResponse;
import com.crm.quota.domain.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quota reserve/release endpoints Q4-Q5 (functional-design.md 3.3, 3.4). Reserve is the Challenge
 * 1 entry point and is idempotent on resourceId (200, not 201); release is exactly-once (BR7).
 */
@RestController
@RequestMapping("/api/v1/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Operation(summary = "Reserve quota atomically (FR-7, Challenge 1)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserved (or replayed)"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
            @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "QUOTA_RESERVATION_ALREADY_RELEASED"),
            @ApiResponse(responseCode = "422", description = "QUOTA_EXCEEDED or TIER_FORBIDS_RESOURCE_TYPE")
    })
    @PostMapping("/reservations")
    public ReserveQuotaResponse reserve(@Valid @RequestBody ReserveQuotaRequest request) {
        return quotaService.reserve(request);
    }

    @Operation(summary = "Release a resource's quota exactly once (FR-8, BR7)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Released (or no-op replay)"),
            @ApiResponse(responseCode = "404", description = "QUOTA_RESERVATION_NOT_FOUND")
    })
    @PostMapping("/reservations/{resourceId}/release")
    public ReleaseQuotaResponse release(@PathVariable @jakarta.validation.constraints.Size(max = 64) String resourceId) {
        return quotaService.release(resourceId);
    }
}

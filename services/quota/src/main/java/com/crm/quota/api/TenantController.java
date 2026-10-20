package com.crm.quota.api;

import com.crm.quota.api.dto.CreateTenantRequest;
import com.crm.quota.api.dto.QuotaUsageResponse;
import com.crm.quota.api.dto.TenantResponse;
import com.crm.quota.domain.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Tenant endpoints Q1-Q3 (functional-design.md 3). Controllers hold the HTTP contract and
 * delegate to the domain service; they contain no business logic.
 */
@RestController
@RequestMapping("/api/v1")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "Create a tenant (FR-1)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
            @ApiResponse(responseCode = "409", description = "TENANT_ALREADY_EXISTS"),
            @ApiResponse(responseCode = "422", description = "INVALID_TIER")
    })
    @PostMapping("/tenants")
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse response = tenantService.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/tenants/" + response.tenantId()))
                .body(response);
    }

    @Operation(summary = "Read a tenant and its tier limits (FR-2)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND")
    })
    @GetMapping("/tenants/{tenantId}")
    public TenantResponse get(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @Operation(summary = "Current usage and headroom (FR-9)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "TENANT_NOT_FOUND")
    })
    @GetMapping("/tenants/{tenantId}/quota")
    public QuotaUsageResponse quota(@PathVariable String tenantId) {
        return tenantService.usage(tenantId);
    }
}

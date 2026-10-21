package com.crm.usage.api;

import com.crm.usage.domain.UsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
public class UsageController {
    private final UsageService service;

    public UsageController(UsageService service) { this.service = service; }

    @GetMapping(value = "/api/v1/usage/{tenantId}", produces = "application/json")
    @Operation(summary = "Compose quota, active resources and current UTC month's cost")
    @ApiResponse(responseCode = "200", description = "Unavailable sections omitted and marked false, including total outage")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: invalid tenantId", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public UsageService.Usage read(@PathVariable @NotBlank @Size(max = 64) String tenantId) {
        return service.read(tenantId);
    }
}

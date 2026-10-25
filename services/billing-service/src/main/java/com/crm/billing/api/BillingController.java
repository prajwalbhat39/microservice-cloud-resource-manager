package com.crm.billing.api;

import com.crm.billing.api.dto.MeterResponse;
import com.crm.billing.api.dto.StartMeterRequest;
import com.crm.billing.api.dto.TenantCostResponse;
import com.crm.billing.service.BillingService;
import com.crm.billing.service.MeterView;
import com.crm.billing.service.StartMeterCommand;
import com.crm.billing.service.StartMeterResult;
import com.crm.billing.service.TenantCostView;
import com.crm.billing.web.BadRequestException;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Meter created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Existing meter replayed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Meter start was cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Billing unavailable or simulated failure")
    })
    @PostMapping("/meters")
    public ResponseEntity<MeterResponse> startMeter(
        @Valid @RequestBody StartMeterRequest request,
        @RequestHeader(name = "X-Simulate-Failure", required = false) String simulateFailure
    ) {
        StartMeterResult result = billingService.startMeter(
            new StartMeterCommand(request.resourceId(), request.tenantId(), request.cpu(), request.ratePerCpuHour()),
            shouldSimulateFailure(simulateFailure)
        );

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toResponse(result.meter()));
    }

    private static boolean shouldSimulateFailure(String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("true") || value.equals("always-fail");
    }

    @PostMapping("/meters/{resourceId}/stop")
    public MeterResponse stopMeter(@PathVariable @jakarta.validation.constraints.Size(max = 64) String resourceId) {
        return toResponse(billingService.stopMeter(resourceId));
    }

    @GetMapping("/meters/{resourceId}")
    public MeterResponse getMeter(@PathVariable @jakarta.validation.constraints.Size(max = 64) String resourceId) {
        return toResponse(billingService.getMeter(resourceId));
    }

    @GetMapping("/tenants/{tenantId}/cost")
    public TenantCostResponse getTenantCost(
        @PathVariable @jakarta.validation.constraints.Size(max = 64) String tenantId,
        @RequestParam(name = "period") String period
    ) {
        if (period == null || !period.matches("[0-9]{4}-[0-9]{2}")) {
            throw new BadRequestException("period is required and must be in YYYY-MM format");
        }

        YearMonth parsedPeriod;
        try {
            parsedPeriod = YearMonth.parse(period);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("period is required and must be in YYYY-MM format");
        }

        TenantCostView tenantCost = billingService.getTenantCost(tenantId, parsedPeriod);
        return new TenantCostResponse(tenantCost.tenantId(), tenantCost.period().toString(), tenantCost.totalCost());
    }

    private MeterResponse toResponse(MeterView meterView) {
        return new MeterResponse(
            meterView.meterId(),
            meterView.resourceId(),
            meterView.tenantId(),
            meterView.cpu(),
            meterView.ratePerCpuHour(),
            meterView.state(),
            meterView.startedAt(),
            meterView.stoppedAt(),
            meterView.currentCost()
        );
    }
}


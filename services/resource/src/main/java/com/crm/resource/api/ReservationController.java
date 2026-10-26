package com.crm.resource.api;

import com.crm.resource.api.dto.ReservationStatusResponse;
import com.crm.resource.domain.service.ReservationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read the provider instance ID from the linked resource row. */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationQueryService reservationQueryService;

    public ReservationController(ReservationQueryService reservationQueryService) {
        this.reservationQueryService = reservationQueryService;
    }

    @Operation(summary = "Poll reservation status (FR-16)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND")
    })
    @GetMapping("/{reservationId}")
    public ReservationStatusResponse get(@PathVariable UUID reservationId) {
        return reservationQueryService.get(reservationId);
    }
}

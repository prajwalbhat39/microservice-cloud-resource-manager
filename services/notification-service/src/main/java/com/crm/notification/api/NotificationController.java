package com.crm.notification.api;

import com.crm.notification.domain.*;
import com.crm.notification.persistence.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/notifications", produces = "application/json")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    public record Request(@NotBlank @Size(max = 64) String tenantId,
            @NotBlank @Size(max = 64) String resourceId, @NotNull EventType eventType,
            @NotBlank @Size(max = 2000) String message) { }

    public record Response(UUID notificationId, String tenantId, String resourceId, EventType eventType,
            DeliveryStatus status, String message, int attempts, Instant createdAt, Instant sentAt) {
        static Response from(Notification n) {
            return new Response(n.getNotificationId(), n.getTenantId(), n.getResourceId(), n.getEventType(),
                    n.getStatus(), n.getMessage(), n.getAttempts(), n.getCreatedAt(), n.getSentAt());
        }
    }

    @PostMapping
    @Operation(summary = "Record and simulate delivery of a lifecycle notification")
    @ApiResponse(responseCode = "202", description = "Durably accepted; delivery may be pending or exhausted")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "NOTIFICATION_UNAVAILABLE: persistence unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Response> record(@Valid @RequestBody Request request) {
        return ResponseEntity.accepted().body(Response.from(service.record(
                request.tenantId(), request.resourceId(), request.eventType(), request.message())));
    }

    @GetMapping
    @Operation(summary = "Read a tenant's notification history")
    @ApiResponse(responseCode = "200", description = "History, including pending and exhausted deliveries")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: tenantId required, at most 64 characters", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "NOTIFICATION_UNAVAILABLE: persistence unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<Response> history(@RequestParam @NotBlank @Size(max = 64) String tenantId) {
        return service.history(tenantId).stream().map(Response::from).toList();
    }
}

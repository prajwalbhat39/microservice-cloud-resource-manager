package com.crm.provider.api;

import com.crm.provider.api.dto.InstanceResponse;
import com.crm.provider.api.dto.ProvisionInstanceRequest;
import com.crm.provider.domain.service.ProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/provider/instances")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid input",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "503", description = "Simulated failure or interrupted operation",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ProviderController {
    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Provision an instance",
            description = "Returns the existing instance for a known clientToken, including after termination. "
                    + "Replays bypass simulation and never create another instance.")
    @ApiResponse(responseCode = "201", description = "Created or replayed instance")
    @ApiResponse(responseCode = "409", description = "Provisioning request was cancelled")
    public InstanceResponse provision(@Valid @RequestBody ProvisionInstanceRequest request,
            @Parameter(description = "always-fail (or true), always-succeed (or false), slow; unknown values use defaults")
            @RequestHeader(value = "X-Simulate-Failure", required = false) String mode) {
        return service.provision(request, mode);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Terminate an instance",
            description = "Retains the instance and client token. Repeated termination returns 200 without simulation.")
    @ApiResponse(responseCode = "200", description = "Terminated or already terminated instance")
    @ApiResponse(responseCode = "404", description = "Unknown instance",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public InstanceResponse terminate(@PathVariable String id,
            @Parameter(description = "Same simulation modes as provisioning")
            @RequestHeader(value = "X-Simulate-Failure", required = false) String mode) {
        return service.terminate(id, mode);
    }
    @DeleteMapping("/requests/{clientToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Request cancelled")
    @Operation(summary = "Cancel provisioning by client token, including requests still in flight")
    public void cancel(@PathVariable @jakarta.validation.constraints.Size(max = 256) String clientToken) {
        service.cancel(clientToken);
    }

}

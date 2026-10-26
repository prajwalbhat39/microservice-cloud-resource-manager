package com.crm.resource.api;

import com.crm.resource.api.dto.ExpiredResourceResponse;
import com.crm.resource.domain.service.ProvisioningService;
import com.crm.resource.domain.service.ResourceQueryService;
import com.crm.resource.domain.service.TerminationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceControllerTest {
    @Test
    void expiredCandidatesUseTheWorkersEnvelope() throws Exception {
        var queries = mock(ResourceQueryService.class);
        var id = UUID.randomUUID();
        when(queries.expired()).thenReturn(List.of(
                new ExpiredResourceResponse(id, "tenant", Instant.parse("2026-01-01T00:00:00Z"))));
        var http = MockMvcBuilders.standaloneSetup(new ResourceController(
                mock(ProvisioningService.class), mock(TerminationService.class), queries)).build();

        http.perform(get("/api/v1/resources/expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resourceId").value(id.toString()))
                .andExpect(jsonPath("$.count").value(1));
    }
}

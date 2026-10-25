package com.crm.billing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crm.billing.domain.MeterState;
import com.crm.billing.service.BillingService;
import com.crm.billing.service.MeterView;
import com.crm.billing.service.StartMeterResult;
import com.crm.billing.web.GlobalExceptionHandler;
import com.crm.billing.web.ServiceUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BillingController.class)
@Import(GlobalExceptionHandler.class)
class BillingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BillingService billingService;

    @Test
    void startMeter_returns201OnCreate() throws Exception {
        MeterView meter = new MeterView(
            UUID.randomUUID(), "res-1", "tenant-1", 2,
            new BigDecimal("0.12"), MeterState.ACTIVE,
            Instant.parse("2026-10-19T00:00:00Z"), null, BigDecimal.ZERO
        );

        when(billingService.startMeter(any(), eq(false))).thenReturn(new StartMeterResult(meter, true));

        mockMvc.perform(post("/api/v1/billing/meters")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "resourceId":"res-1",
                      "tenantId":"tenant-1",
                      "cpu":2,
                      "ratePerCpuHour":0.12
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resourceId").value("res-1"))
            .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void startMeter_withHeaderFailure_returns503() throws Exception {
        when(billingService.startMeter(any(), eq(true)))
            .thenThrow(new ServiceUnavailableException("Simulated failure"));

        mockMvc.perform(post("/api/v1/billing/meters")
                .header("X-Simulate-Failure", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "resourceId":"res-2",
                      "tenantId":"tenant-2",
                      "cpu":1
                    }
                    """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
    }

    @Test
    void tenantCost_periodRequiredAndValidated_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/billing/tenants/tenant-1/cost"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/billing/tenants/tenant-1/cost").param("period", "2026/10"))
            .andExpect(status().isBadRequest());
    }    @Test
    void storagePrecisionAndFrameworkErrorsAreClientErrors() throws Exception {
        mockMvc.perform(post("/api/v1/billing/meters").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"r\",\"tenantId\":\"t\",\"cpu\":1,\"ratePerCpuHour\":1000000}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/billing/meters/" + "x".repeat(65) + "/stop"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/billing/meters").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/billing/meters").contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(get("/api/v1/billing/tenants/tenant/cost").param("period", "+999999999-12"))
                .andExpect(status().isBadRequest());
    }

}


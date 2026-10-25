package com.crm.resource.client;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BillingClientTest {
    @ParameterizedTest
    @CsvSource({"always-fail,always-fail", "always-succeed,always-succeed"})
    void forwardsSimulationModesToBilling(String mode, String header) {
        var builder = RestClient.builder().baseUrl("http://billing");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://billing/api/v1/billing/meters"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Simulate-Failure", header))
                .andRespond(withSuccess());
        var client = new BillingClient(builder, new PeerRetry(1, 0, 1, 0, 0));

        client.startMeter("resource", "tenant", 2, new BigDecimal("0.12"), mode);

        server.verify();
    }
}

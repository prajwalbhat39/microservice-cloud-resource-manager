package com.crm.resource;

import com.crm.resource.domain.service.ResourceWork;
import com.crm.resource.domain.service.ResourceWorkRunner;
import com.crm.resource.domain.service.ReservationService;
import com.crm.resource.persistence.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecoveryIT extends AbstractResourceIT {
    @Autowired ResourceWorkRunner runner;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "sagaExecutor") java.util.concurrent.Executor executor;
    @MockitoSpyBean ResourceRepository resources;
    @MockitoSpyBean ReservationService reservations;
    @Autowired ResourceWork work;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void pauseImmediateDispatch() { doThrow(new org.springframework.core.task.TaskRejectedException("Executor unavailable")).when(executor).execute(any()); }

    private JsonNode accept() throws Exception {
        var response = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(UUID.randomUUID().toString(), provisionBody("recovery-tenant", "VM", 2, 4)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
    }

    @Test
    void committedAcceptanceRunsWithoutItsOriginalExecutorSubmission() throws Exception {
        var accepted = accept();
        UUID id = UUID.fromString(accepted.path("resourceId").asText());
        assertThat(resources.findById(id).orElseThrow().getStatus()).isEqualTo("REQUESTED");
        assertThat(work.pending()).contains(id);
        runner.recover();
        assertThat(resources.findById(id).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(work.pending()).doesNotContain(id);
    }

    @Test
    void exhaustedCleanupRetainsItsIdentityUntilPeersRecover() throws Exception {
        peers.stubFor(post(urlEqualTo("/api/v1/billing/meters")).willReturn(aResponse().withStatus(503)));
        peers.stubFor(delete(urlPathMatching("/api/v1/provider/instances/requests/.*")).willReturn(aResponse().withStatus(503)));
        var accepted = accept();
        UUID id = UUID.fromString(accepted.path("resourceId").asText());
        runner.recover();
        var pending = resources.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo("PROVISIONING");
        assertThat(pending.getProviderInstanceId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT kind FROM resource_work WHERE resource_id = ?", String.class, id)).isEqualTo("COMPENSATE");
        peers.verify(0, postRequestedFor(urlEqualTo("/api/v1/quota/reservations/" + id + "/release")));

        peers.stubFor(delete(urlPathMatching("/api/v1/provider/instances/requests/.*")).willReturn(aResponse().withStatus(204)));
        runner.recover();
        assertThat(resources.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(resources.findById(id).orElseThrow().getProviderInstanceId()).isNull();
        assertThat(work.pending()).doesNotContain(id);
        peers.verify(1, postRequestedFor(urlEqualTo("/api/v1/quota/reservations/" + id + "/release")));
    }

    @Test
    void activationRollsBackWhenReservationCompletionFails() throws Exception {
        doThrow(new IllegalStateException("Completion write failed")).when(org.springframework.test.util.AopTestUtils.<ReservationService>getUltimateTargetObject(reservations)).complete(any());
        var accepted = accept();
        UUID id = UUID.fromString(accepted.path("resourceId").asText());
        runner.recover();
        assertThat(resources.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(work.pending()).doesNotContain(id);
    }

    @Test
    void localPersistenceFailureCannotLoseTheRemoteQuotaRelease() {
        doThrow(new IllegalStateException("Local write failed")).when(resources).save(any());
        var response = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request(UUID.randomUUID().toString(), provisionBody("recovery-tenant", "VM", 2, 4)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        UUID id;
        try {
            var reserve = peers.findAll(postRequestedFor(urlEqualTo("/api/v1/quota/reservations"))).getFirst();
            id = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(reserve.getBodyAsString()).path("resourceId").asText());
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        var orphaned = java.util.List.of(id);
        assertThat(work.pending()).contains(id);
        runner.recover();
        assertThat(work.pending()).doesNotContain(orphaned.getFirst());
        peers.verify(1, postRequestedFor(urlEqualTo("/api/v1/quota/reservations/" + orphaned.getFirst() + "/release")));
    }
    @Test
    void migrationCompletesLegacyActiveReservationsWithoutReprovisioning() throws Exception {
        UUID id = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO resources(resource_id, tenant_id, type, cpu, ram_gb, status, activated_at) VALUES (?, 'legacy', 'VM', 1, 1, 'ACTIVE', now())", id);
        jdbc.update("INSERT INTO reservations(reservation_id, tenant_id, resource_id, status, updated_at) VALUES (?, 'legacy', ?, 'PENDING', now())", reservation, id);
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V3__complete_legacy_activations.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE reservation_id = ?", String.class, reservation)).isEqualTo("COMPLETED");
        assertThat(resources.findById(id).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void frameworkErrorsAndOversizedKeysNeverReachPeers() {
        var invalidKey = rest.exchange("/api/v1/resources", HttpMethod.POST,
                request("x".repeat(201), provisionBody("recovery-tenant", "VM", 2, 4)), String.class);
        assertThat(invalidKey.getStatusCode().value()).isEqualTo(400);
        assertThat(invalidKey.getBody()).contains("idempotencyKey");
        assertThat(rest.getForEntity("/api/v1/resources/not-a-uuid", String.class).getStatusCode().value()).isEqualTo(400);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        var media = rest.postForEntity("/api/v1/resources", new org.springframework.http.HttpEntity<>("plain", headers), String.class);
        assertThat(media.getStatusCode().value()).isEqualTo(415);
        assertThat(peers.getAllServeEvents()).isEmpty();
    }

}

package com.crm.provider;

import com.crm.provider.api.dto.InstanceResponse;
import com.crm.provider.api.dto.ProvisionInstanceRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "provider.failure-rate=0",
        "provider.delay-min-ms=0",
        "provider.delay-max-ms=0",
        "provider.slow-delay-ms=150"
})
@Import(ProviderApiIT.DatabaseConfiguration.class)
class ProviderApiIT {
    private static final String API = "/api/v1/provider/instances";

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean(destroyMethod = "close")
        EmbeddedPostgres embeddedPostgres() throws IOException {
            return EmbeddedPostgres.builder().setPort(0).start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres postgres) {
            JdbcTemplate admin = new JdbcTemplate(postgres.getPostgresDatabase());
            admin.execute("CREATE ROLE provider_user LOGIN");
            admin.execute("CREATE DATABASE provider_db OWNER provider_user");
            return postgres.getDatabase("provider_user", "provider_db");
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    private static ProvisionInstanceRequest request(String token) {
        return new ProvisionInstanceRequest(token, "VM", 2, 4);
    }

    private ResponseEntity<InstanceResponse> provision(ProvisionInstanceRequest request, String mode) {
        HttpHeaders headers = new HttpHeaders();
        if (mode != null) {
            headers.set("X-Simulate-Failure", mode);
        }
        return rest.postForEntity(API, new HttpEntity<>(request, headers), InstanceResponse.class);
    }

    private ResponseEntity<InstanceResponse> terminate(String id, String mode) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulate-Failure", mode);
        return rest.exchange(API + "/{id}", HttpMethod.DELETE, new HttpEntity<>(headers), InstanceResponse.class, id);
    }

    @Test
    void healthIncludesDatabaseConnectivity() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/health", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().path("components").path("db").path("status").asText()).isEqualTo("UP");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("provider_db");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("provider_user");
    }

    @Test
    void fr19AndFr43_repeatedTokenReturnsOnePersistedInstance() {
        String token = UUID.randomUUID().toString();
        ResponseEntity<InstanceResponse> first = provision(request(token), null);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().instanceId()).matches("i-mock-[0-9a-f-]{36}");
        assertThat(first.getBody().status()).isEqualTo("ACTIVE");

        ResponseEntity<InstanceResponse> replay = provision(
                new ProvisionInstanceRequest(token, "CLUSTER", 8, 16), "always-fail");
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instances WHERE client_token = ?", Integer.class, token))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM instances WHERE client_token = ?", String.class, token))
                .isEqualTo("VM");
    }

    @Test
    void concurrentProvisioningCreatesExactlyOneInstance() throws Exception {
        String token = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<InstanceResponse>>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent provision start timed out");
                    }
                    return provision(request(token), "always-succeed");
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (var future : futures) {
                ResponseEntity<InstanceResponse> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().value()).isEqualTo(201);
                ids.add(response.getBody().instanceId());
            }
            assertThat(ids).hasSize(16).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instances WHERE client_token = ?", Integer.class, token))
                .isEqualTo(1);
    }

    @Test
    void databaseItselfRejectsDuplicateClientTokens() {
        String token = UUID.randomUUID().toString();
        provision(request(token), "always-succeed");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO instances (instance_id, client_token, type, cpu, ram_gb, status)
                VALUES ('duplicate', ?, 'VM', 2, 4, 'ACTIVE')
                """, token)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"always-fail", "true"})
    void fr42_forcedFailureIsCorrelatedAndDoesNotPersist(String mode) {
        String token = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulate-Failure", mode);
        headers.set("X-Correlation-Id", "provider-it");
        ResponseEntity<JsonNode> response = rest.postForEntity(API, new HttpEntity<>(request(token), headers), JsonNode.class);
        assertProblem(response, 503, "SIMULATED_FAILURE");
        assertThat(response.getBody().path("correlationId").asText()).isEqualTo("provider-it");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instances WHERE client_token = ?", Integer.class, token))
                .isZero();
        assertThat(provision(request(token), "always-succeed").getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void slowModeActuallyWaitsForConfiguredDuration() {
        long start = System.nanoTime();
        ResponseEntity<InstanceResponse> response = provision(request(UUID.randomUUID().toString()), "slow");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);
    }

    @Test
    void terminationIsIdempotentAndDoesNotResurrectToken() {
        String token = UUID.randomUUID().toString();
        String id = provision(request(token), "always-succeed").getBody().instanceId();
        ResponseEntity<InstanceResponse> first = terminate(id, "always-succeed");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().status()).isEqualTo("TERMINATED");
        OffsetDateTime stamp = jdbc.queryForObject(
                "SELECT terminated_at FROM instances WHERE instance_id = ?", OffsetDateTime.class, id);
        assertThat(stamp).isNotNull();

        ResponseEntity<InstanceResponse> replay = terminate(id, "always-fail");
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.queryForObject("SELECT terminated_at FROM instances WHERE instance_id = ?",
                OffsetDateTime.class, id)).isEqualTo(stamp);
        assertThat(provision(request(token), "always-fail").getBody()).isEqualTo(first.getBody());
    }

    @Test
    void forcedTerminationFailureLeavesInstanceActive() {
        String id = provision(request(UUID.randomUUID().toString()), "always-succeed").getBody().instanceId();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulate-Failure", "always-fail");
        ResponseEntity<JsonNode> response = rest.exchange(API + "/{id}", HttpMethod.DELETE,
                new HttpEntity<>(headers), JsonNode.class, id);
        assertProblem(response, 503, "SIMULATED_FAILURE");
        assertThat(jdbc.queryForObject("SELECT status FROM instances WHERE instance_id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }

    @Test
    void unknownTerminationReturnsProblemDetails() {
        ResponseEntity<JsonNode> response = rest.exchange(API + "/missing", HttpMethod.DELETE,
                HttpEntity.EMPTY, JsonNode.class);
        assertProblem(response, 404, "INSTANCE_NOT_FOUND");
    }

    @Test
    void invalidFieldsAreNamedAndCorrelationIsGenerated() {
        ResponseEntity<JsonNode> response = rest.postForEntity(API,
                Map.of("clientToken", " ", "type", "invalid", "cpu", 0, "ramGb", -1), JsonNode.class);
        assertProblem(response, 400, "VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        response.getBody().path("errors").forEach(error -> fields.add(error.path("field").asText()));
        assertThat(fields).contains("clientToken", "type", "cpu", "ramGb");
    }

    @Test
    void missingFieldsAreRejected() {
        ResponseEntity<JsonNode> response = rest.postForEntity(API, Map.of(), JsonNode.class);
        assertProblem(response, 400, "VALIDATION_FAILED");
    }

    @Test
    void malformedJsonReturnsProblemDetails() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<JsonNode> response = rest.postForEntity(API, new HttpEntity<>("{", headers), JsonNode.class);
        assertProblem(response, 400, "MALFORMED_REQUEST");
    }

    @Test
    void wrongMethodReturnsProblemDetails() {
        assertProblem(rest.getForEntity(API, JsonNode.class), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void unknownPathReturnsProblemDetails() {
        assertProblem(rest.getForEntity("/api/v1/not-found", JsonNode.class), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void openApiIsGeneratedFromRunningService() throws IOException {
        ResponseEntity<JsonNode> json = rest.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(json.getStatusCode().value()).isEqualTo(200);
        JsonNode spec = json.getBody();
        assertThat(spec.path("info").path("title").asText()).isEqualTo("Provider Mock Service API");
        assertThat(spec.path("paths").path(API).path("post").path("responses").has("201")).isTrue();
        assertThat(spec.path("paths").path(API + "/{id}").path("delete").path("responses").has("404")).isTrue();
        assertThat(spec.path("components").path("schemas").path("ProvisionInstanceRequest").path("required"))
                .isEqualTo(mapper.readTree("[\"clientToken\",\"cpu\",\"ramGb\",\"type\"]"));
        ResponseEntity<String> yaml = rest.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(yaml.getStatusCode().value()).isEqualTo(200);
        assertThat(yaml.getBody()).contains("openapi:", API);
        String exportPath = System.getProperty("provider.export-openapi");
        if (exportPath != null) {
            // Springdoc includes the random test server URL; keep the committed spec portable.
            String portable = yaml.getBody().replaceAll(
                    "(?m)^([ \\t]*- url:) \"?http://localhost:\\d+\"?$", "$1 http://localhost:8085");
            assertThat(portable).contains("- url: http://localhost:8085");
            Files.writeString(Path.of(exportPath), portable);
        }
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode body = response.getBody();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("type").asText()).startsWith("https://crm.example/errors/");
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("detail").asText()).isNotBlank();
        assertThat(body.path("instance").asText()).startsWith("/");
        assertThat(body.path("timestamp").asText()).isNotBlank();
        assertThat(body.path("correlationId").asText()).isNotBlank()
                .isEqualTo(response.getHeaders().getFirst("X-Correlation-Id"));
    }
}

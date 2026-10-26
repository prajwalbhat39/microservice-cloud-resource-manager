package com.crm.resource.domain.service;

import com.crm.resource.client.QuotaClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResourceWorkRunner {
    private static final Logger log = LoggerFactory.getLogger(ResourceWorkRunner.class);
    private final ResourceWork work;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProvisioningSaga saga;
    private final TerminationTeardown teardown;
    private final QuotaClient quota;

    public ResourceWorkRunner(ResourceWork work, JdbcTemplate jdbc, ObjectMapper mapper,
            ProvisioningSaga saga, TerminationTeardown teardown, QuotaClient quota) {
        this.work = work; this.jdbc = jdbc; this.mapper = mapper;
        this.saga = saga; this.teardown = teardown; this.quota = quota;
    }

    @Async("sagaExecutor")
    public void dispatch(UUID id) { execute(id); }

    @Scheduled(fixedDelayString = "${recovery.interval-ms:30000}", initialDelayString = "${recovery.interval-ms:30000}")
    public void recover() { for (UUID id : work.pending()) execute(id); }

    private void execute(UUID id) {
        // A session lock covers HTTP calls without keeping a database transaction open.
        try (var ignored = MDC.putCloseable("correlationId", UUID.randomUUID().toString())) {
            try (var connection = work.independentConnection()) {
                if (!lock(connection, id)) return;
                try {
                    var rows = jdbc.queryForList("SELECT kind, payload FROM resource_work WHERE resource_id = ?", id);
                    if (rows.isEmpty()) return;
                    var row = rows.getFirst();
                    String payload = (String) row.get("payload");
                    switch ((String) row.get("kind")) {
                        case "QUOTA_RELEASE" -> { quota.release(id.toString()); work.done(id); }
                        case "PROVISION" -> saga.run(mapper.readValue(payload, ProvisioningTask.class));
                        case "COMPENSATE" -> saga.compensate(mapper.readValue(payload, ResourceWork.Compensation.class));
                        case "TERMINATE" -> teardown.run(mapper.readValue(payload, TerminationTask.class));
                        default -> throw new IllegalStateException("Unknown work kind");
                    }
                } catch (Exception failure) {
                    jdbc.update("UPDATE resource_work SET updated_at = now() WHERE resource_id = ?", id);
                    log.warn("resource.work.pending resourceId={} cause={}", id, failure.getClass().getSimpleName());
                }
            } catch (SQLException failure) {
                log.warn("resource.work.connection.failed resourceId={}", id);
            }
        }
    }

    private boolean lock(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }
}

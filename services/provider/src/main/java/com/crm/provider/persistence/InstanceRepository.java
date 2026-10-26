package com.crm.provider.persistence;

import com.crm.provider.api.dto.InstanceResponse;
import com.crm.provider.api.dto.ProvisionInstanceRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InstanceRepository {
    private final JdbcTemplate jdbc;

    public InstanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InstanceResponse> findByClientToken(String token) {
        return jdbc.query("SELECT instance_id, status FROM instances WHERE client_token = ?",
                (rs, row) -> new InstanceResponse(rs.getString("instance_id"), rs.getString("status")), token)
                .stream().findFirst();
    }

    public Optional<InstanceResponse> findById(String id) {
        return jdbc.query("SELECT instance_id, status FROM instances WHERE instance_id = ?",
                (rs, row) -> new InstanceResponse(rs.getString("instance_id"), rs.getString("status")), id)
                .stream().findFirst();
    }

    @Transactional
    public InstanceResponse createOrGet(ProvisionInstanceRequest request) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> { }, request.clientToken());
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cancelled_requests WHERE client_token = ?)", Boolean.class, request.clientToken()))) {
            throw new com.crm.provider.common.error.ProviderException(
                    com.crm.provider.common.error.ErrorCode.INSTANCE_REQUEST_CANCELLED, "Provision request was cancelled");
        }
        // ON CONFLICT waits for a competing insert; the following READ COMMITTED statement
        // sees its committed row. No caught constraint error or poisoned transaction is needed.
        jdbc.update("""
                INSERT INTO instances (instance_id, client_token, type, cpu, ram_gb, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (client_token) DO NOTHING
                """, "i-mock-" + UUID.randomUUID(), request.clientToken(), request.type(),
                request.cpu(), request.ramGb());
        return findByClientToken(request.clientToken())
                .orElseThrow(() -> new IllegalStateException("Instance missing after idempotent insert"));
    }

    public void terminate(String id) {
        jdbc.update("""
                UPDATE instances SET status = 'TERMINATED', terminated_at = now()
                WHERE instance_id = ? AND status = 'ACTIVE'
                """, id);
    }

    @Transactional
    public void cancel(String token) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> { }, token);
        jdbc.update("INSERT INTO cancelled_requests(client_token) VALUES (?) ON CONFLICT DO NOTHING", token);
        jdbc.update("UPDATE instances SET status = 'TERMINATED', terminated_at = now() WHERE client_token = ? AND status = 'ACTIVE'", token);
    }
}

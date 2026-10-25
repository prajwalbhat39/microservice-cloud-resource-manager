package com.crm.resource.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable work belongs to the same database as the resource lifecycle. */
@Service
public class ResourceWork {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DataSourceProperties database;

    public ResourceWork(JdbcTemplate jdbc, ObjectMapper mapper, DataSourceProperties database) {
        this.jdbc = jdbc; this.mapper = mapper; this.database = database;
    }

    public void rememberQuota(UUID id) {
        // Commit recovery intent independently without borrowing a second pooled connection.
        try (var connection = independentConnection();
             var statement = connection.prepareStatement("INSERT INTO resource_work(resource_id, kind, payload) VALUES (?, 'QUOTA_RELEASE', '{}')")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new org.springframework.jdbc.CannotGetJdbcConnectionException("Cannot persist quota recovery intent", failure);
        }
    }

    Connection independentConnection() throws SQLException {
        var properties = new java.util.Properties();
        properties.setProperty("user", database.determineUsername());
        properties.setProperty("password", database.determinePassword());
        properties.setProperty("connectTimeout", "2");
        return DriverManager.getConnection(database.determineUrl(), properties);
    }

    public void enqueue(UUID id, String kind, Object payload) {
        try {
            jdbc.update("INSERT INTO resource_work(resource_id, kind, payload) VALUES (?, ?, ?) "
                    + "ON CONFLICT(resource_id) DO UPDATE SET kind = EXCLUDED.kind, payload = EXCLUDED.payload, updated_at = now()",
                    id, kind, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot persist resource work", failure);
        }
    }

    public void done(UUID id) { jdbc.update("DELETE FROM resource_work WHERE resource_id = ?", id); }

    public List<UUID> pending() {
        return jdbc.query("SELECT resource_id FROM resource_work ORDER BY updated_at LIMIT 100",
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    public record Compensation(ProvisioningTask task, String reason) { }
}

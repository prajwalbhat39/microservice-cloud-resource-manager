package com.crm.quota;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Uses a dedicated external test database, or starts PostgreSQL through Testcontainers. */
public final class PostgresSingleton {
    private PostgresSingleton() { }

    public static boolean available() {
        return System.getenv("QUOTA_TEST_DB_URL") != null || DockerClientFactory.instance().isDockerAvailable();
    }

    private static class Container {
        static final PostgreSQLContainer<?> INSTANCE = start();
        static PostgreSQLContainer<?> start() {
            var database = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("quota_db").withUsername("quota_user").withPassword("changeme");
            database.start();
            return database;
        }
    }

    public static String url() {
        String value = System.getenv("QUOTA_TEST_DB_URL");
        return value != null ? value : Container.INSTANCE.getJdbcUrl();
    }

    public static String username() {
        return System.getenv().getOrDefault("QUOTA_TEST_DB_USER", "quota_user");
    }

    public static String password() {
        return System.getenv().getOrDefault("QUOTA_TEST_DB_PASSWORD", "changeme");
    }
}

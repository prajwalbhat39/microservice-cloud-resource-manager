package com.crm.resource;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole JVM — the Testcontainers "singleton container" pattern.
 *
 * <p>Deliberately NOT a {@code @Container} field: the JUnit extension manages a static
 * {@code @Container} <em>per test class</em>, stopping it in {@code afterAll}. All the IT classes
 * here share one cached Spring context (same annotations, same property source), so the second
 * class would inherit a DataSource pointing at a container that had already been stopped — which
 * surfaces as {@code HikariPool-1 - Connection is not available ... (total=0)} rather than as
 * anything resembling the real cause.
 *
 * <p>Initialised lazily on first reference, which happens from {@code @DynamicPropertySource} —
 * i.e. after JUnit has evaluated {@code disabledWithoutDocker}, so a machine without Docker skips
 * the tests instead of failing in a static initialiser. Cleanup is left to Ryuk and JVM exit.
 */
final class PostgresSingleton {

    static final PostgreSQLContainer<?> INSTANCE = start();

    private PostgresSingleton() {
    }

    @SuppressWarnings("resource")   // intentionally outlives every test class
    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("resource_db")
                .withUsername("resource_user")
                .withPassword("changeme");
        container.start();
        return container;
    }
}

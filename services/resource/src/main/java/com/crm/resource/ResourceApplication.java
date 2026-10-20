package com.crm.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Resource / Orchestrator service entry point (port 8082, database resource_db).
 *
 * <p>The tenant-facing entry point: owns resource lifecycle, async reservations and idempotency
 * records; drives the provisioning saga. Carries Challenge 2 (idempotency) and Challenge 3C
 * (saga with compensation). Enforces BR2, BR3, BR4, BR6.
 *
 * <p>{@code @EnableAsync} backs the saga executor (nfr-design.md 3.3); {@code @EnableScheduling}
 * backs the idempotency-record retention sweep (nfr-design.md 9, D5).
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ResourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResourceApplication.class, args);
    }
}

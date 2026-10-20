package com.crm.quota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Quota service entry point (port 8081, database quota_db).
 *
 * <p>Owns tenants, tiers, quota limits/usage and the reservation ledger. Carries Challenge 1
 * (concurrency control via an atomic conditional UPDATE) and enforces BR1, BR7 and BR8. This
 * service is also the reference implementation of the cross-cutting patterns (error model,
 * correlation-id filter, structured logging, config, Flyway layout) that U2-U4 copy.
 */
@SpringBootApplication
public class QuotaApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuotaApplication.class, args);
    }
}

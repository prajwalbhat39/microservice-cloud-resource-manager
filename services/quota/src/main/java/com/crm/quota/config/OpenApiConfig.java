package com.crm.quota.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc wiring (NFR-11). The spec is served live at /v3/api-docs.yaml and derived from the
 * annotated controllers; the committed openapi.yaml is exported from there (see README).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI quotaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Quota Service API")
                .version("v1")
                .description("Tenants, tiers and the quota reservation ledger. Challenge 1 (concurrency)."));
    }
}

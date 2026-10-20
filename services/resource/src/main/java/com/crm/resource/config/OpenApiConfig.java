package com.crm.resource.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc wiring (NFR-11). Spec served live at /v3/api-docs.yaml, derived from annotated controllers. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI resourceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Resource / Orchestrator Service API")
                .version("v1")
                .description("Resource lifecycle, idempotent provisioning (Challenge 2), and the "
                        + "saga with compensation (Challenge 3C)."));
    }
}

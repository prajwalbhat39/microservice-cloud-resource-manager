package com.crm.usage.config;

import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenApiCustomizer responseContracts() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            if (schemas != null && schemas.containsKey("ProblemDetail")) {
                var problem = schemas.get("ProblemDetail");
                problem.getProperties().remove("properties");
                problem.addProperty("code", new StringSchema());
                problem.addProperty("correlationId", new StringSchema());
                problem.addProperty("timestamp", new DateTimeSchema());
                problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "correlationId", "timestamp"));
                problem.setAdditionalProperties(true);
            }
            var health = api.getPaths().get("/health").getGet();
            health.getResponses().addApiResponse("503", new ApiResponse()
                    .description("Service or its database is unavailable")
                    .content(health.getResponses().get("200").getContent()));
        };
    }
}

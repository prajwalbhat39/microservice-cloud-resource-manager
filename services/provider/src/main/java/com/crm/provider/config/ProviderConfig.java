package com.crm.provider.config;

import com.crm.provider.domain.service.Sleeper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;
import java.util.random.RandomGenerator;

@Configuration
public class ProviderConfig {
    @Bean
    public RandomGenerator providerRandom() {
        return new Random();
    }

    @Bean
    public Sleeper providerSleeper() {
        return Thread::sleep;
    }

    @Bean
    public OpenAPI providerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Provider Mock Service API")
                .version("v1")
                .description("Idempotent instance provisioning and termination with simulated delays and failures."));
    }
}

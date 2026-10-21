package com.crm.provider.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPropertiesTest {
    @Test
    void rejectsInvalidRatesNegativeDelaysAndInvertedBounds() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new ProviderProperties(0.2, 1000, 3000, 10000))).isEmpty();
            assertThat(validator.validate(new ProviderProperties(0, 0, 0, 0))).isEmpty();
            assertThat(validator.validate(new ProviderProperties(1, 0, 0, 0))).isEmpty();
            assertThat(validator.validate(new ProviderProperties(-0.1, 0, 0, 0))).isNotEmpty();
            assertThat(validator.validate(new ProviderProperties(1.1, 0, 0, 0))).isNotEmpty();
            assertThat(validator.validate(new ProviderProperties(Double.NaN, 0, 0, 0))).isNotEmpty();
            assertThat(validator.validate(new ProviderProperties(0, -1, 0, 0))).isNotEmpty();
            assertThat(validator.validate(new ProviderProperties(0, 2, 1, 0))).isNotEmpty();
            assertThat(validator.validate(new ProviderProperties(0, 0, 0, -1))).isNotEmpty();
        }
    }
}

package com.crm.quota.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backing validator for {@link ValueOfEnum}. A null value passes (combine with {@code @NotBlank}
 * when the field is required) so the two concerns stay separate and produce distinct messages.
 */
public class ValueOfEnumValidator implements ConstraintValidator<ValueOfEnum, CharSequence> {

    private List<String> acceptedValues;
    private String message;

    @Override
    public void initialize(ValueOfEnum annotation) {
        acceptedValues = Stream.of(annotation.value().getEnumConstants())
                .map(Enum::name)
                .toList();
        this.message = annotation.message();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        boolean valid = acceptedValues.contains(value.toString());
        if (!valid) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "must be one of " + Arrays.toString(acceptedValues.toArray()))
                    .addConstraintViolation();
        }
        return valid;
    }
}

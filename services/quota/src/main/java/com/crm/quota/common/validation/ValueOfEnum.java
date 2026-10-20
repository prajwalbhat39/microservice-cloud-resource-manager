package com.crm.quota.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a String is the name of a constant in the given enum, producing a 400
 * VALIDATION_FAILED (not Jackson's deserialisation error) with a message naming the allowed
 * values. Used where the contract error for a bad enum value is a plain 400 (e.g. ResourceType
 * on the reserve request), as opposed to Tier, whose contract error is a business 422.
 */
@Documented
@Constraint(validatedBy = ValueOfEnumValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValueOfEnum {

    Class<? extends Enum<?>> value();

    String message() default "must be one of {enumValues}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

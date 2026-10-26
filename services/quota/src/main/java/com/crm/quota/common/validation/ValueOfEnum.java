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

/** Keep invalid enum strings in the validation error contract. */
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

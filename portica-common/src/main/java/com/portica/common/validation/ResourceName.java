package com.portica.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates an immutable resource name (organization, environment, proxy, product, ...).
 * <p>
 * Names follow DNS-label rules so they are safe in URLs, Kubernetes resources and hostnames:
 * 1-63 characters, lowercase letters, digits and hyphens, starting and ending with a letter or digit.
 * {@code null} is considered valid; combine with {@code @NotBlank} when required.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = ResourceName.PATTERN)
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.METHOD, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceName {

    String PATTERN = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$";

    String message() default "must be 1-63 characters of lowercase letters, digits and hyphens, "
            + "starting and ending with a letter or digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

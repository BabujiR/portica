package com.portica.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void typeUriIsKebabCase() {
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.typeUri())
                .isEqualTo("https://docs.portica.dev/problems/resource-not-found");
    }

    @Test
    void exceptionsCarryTheirCode() {
        assertThat(new ResourceNotFoundException("Organization", "acme").errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(new ResourceNotFoundException("Organization", "acme").getMessage())
                .isEqualTo("Organization 'acme' was not found");
        assertThat(ResourceConflictException.alreadyExists("Environment", "dev").errorCode().httpStatus())
                .isEqualTo(409);
        assertThat(new BusinessRuleViolationException("x").errorCode().httpStatus()).isEqualTo(422);
    }
}

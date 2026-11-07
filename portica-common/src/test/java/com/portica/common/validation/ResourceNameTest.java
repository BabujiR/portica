package com.portica.common.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceNameTest {

    record Named(@ResourceName String name) {
    }

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "dev", "prod-eu-1", "acme", "0", "a1b2"})
    void acceptsValidNames(String name) {
        assertThat(validator.validate(new Named(name))).isEmpty();
    }

    @Test
    void acceptsNamesOfExactly63Characters() {
        assertThat(validator.validate(new Named("x".repeat(63)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-dev", "dev-", "Dev", "dev_test", "dev.test", "dev test", "é"})
    void rejectsInvalidNames(String name) {
        var violations = validator.validate(new Named(name));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("lowercase letters");
    }

    @Test
    void rejectsNamesLongerThan63Characters() {
        assertThat(validator.validate(new Named("x".repeat(64)))).hasSize(1);
    }

    @Test
    void nullIsValidSoItCanBeCombinedWithNotBlank() {
        assertThat(validator.validate(new Named(null))).isEmpty();
    }
}

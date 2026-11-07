package com.portica.gateway.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentSelectorTest {

    @Test
    void wildcardMatchesEverything() {
        assertThat(new EnvironmentSelector(List.of("*")).matches("acme", "prod")).isTrue();
    }

    @Test
    void organizationWildcardMatchesOnlyThatOrganization() {
        EnvironmentSelector selector = new EnvironmentSelector(List.of("acme/*"));
        assertThat(selector.matches("acme", "dev")).isTrue();
        assertThat(selector.matches("globex", "dev")).isFalse();
    }

    @Test
    void exactPatternsMatchExactly() {
        EnvironmentSelector selector = new EnvironmentSelector(List.of("acme/prod", " globex/prod "));
        assertThat(selector.matches("acme", "prod")).isTrue();
        assertThat(selector.matches("globex", "prod")).isTrue();
        assertThat(selector.matches("acme", "dev")).isFalse();
        assertThat(selector.matches("acme-two", "prod")).isFalse();
    }
}

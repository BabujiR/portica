package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.KeyValueMapOperationsConfig;
import com.portica.common.policy.config.KeyValueMapOperationsConfig.Get;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyValueMapOperationsPolicyTest {

    private final RuntimeKeyValueMap config = new RuntimeKeyValueMap("backend", "environment", false,
            Map.of("url", "https://backend.internal", "tenant-a.plan", "gold"));

    @Test
    void copiesEntriesIntoVariablesWithTemplatedKeysAndDefaults() {
        KeyValueMapOperationsPolicy policy = new KeyValueMapOperationsPolicy("kvm", new KeyValueMapOperationsConfig(
                "backend", null, List.of(new Get("url", "backend.url", null),
                new Get("{request.header.x-tenant}.plan", "tenant.plan", "free"),
                new Get("missing", "other", null))), config);

        MessageContext gold = request("GET", "/").header("x-tenant", "tenant-a").build();
        assertThat(run(policy, gold)).isNull();
        assertThat(gold.variable("backend.url")).isEqualTo("https://backend.internal");
        assertThat(gold.variable("tenant.plan")).isEqualTo("gold");
        assertThat(gold.variable("other")).isNull();

        MessageContext unknown = request("GET", "/").header("x-tenant", "tenant-z").build();
        run(policy, unknown);
        assertThat(unknown.variable("tenant.plan")).isEqualTo("free");
    }

    @Test
    void missingMapsYieldDefaults() {
        KeyValueMapOperationsPolicy policy = new KeyValueMapOperationsPolicy("kvm", new KeyValueMapOperationsConfig(
                "nope", "organization", List.of(new Get("x", "x.value", "fallback"))), null);
        MessageContext ctx = request("GET", "/").build();
        run(policy, ctx);
        assertThat(ctx.variable("x.value")).isEqualTo("fallback");
    }

    @Test
    void encryptedValuesOnlyGoToPrivateVariables() {
        RuntimeKeyValueMap secrets = new RuntimeKeyValueMap("secrets", "environment", true, Map.of("token", "t0p"));

        KeyValueMapOperationsPolicy ok = new KeyValueMapOperationsPolicy("kvm", new KeyValueMapOperationsConfig(
                "secrets", null, List.of(new Get("token", "private.backend.token", null))), secrets);
        MessageContext ctx = request("GET", "/").build();
        run(ok, ctx);
        assertThat(ctx.variable("private.backend.token")).isEqualTo("t0p");

        assertThatThrownBy(() -> new KeyValueMapOperationsPolicy("kvm", new KeyValueMapOperationsConfig(
                "secrets", null, List.of(new Get("token", "backend.token", null))), secrets))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private.");
    }

    @Test
    void configValidation() {
        assertThat(new KeyValueMapOperationsConfig("backend", null, List.of(new Get("k", "v", null))).validate()).isEmpty();
        assertThat(new KeyValueMapOperationsConfig("Bad Name", "galaxy", List.of()).validate()).hasSize(3);
        assertThat(new KeyValueMapOperationsConfig("ok", null, List.of(new Get("", "not a var!", null))).validate())
                .hasSize(2);
    }
}

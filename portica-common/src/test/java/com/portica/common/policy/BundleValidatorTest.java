package com.portica.common.policy;

import com.portica.common.policy.config.QuotaConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleValidatorTest {

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, config);
    }

    @Test
    void validBundlePasses() {
        List<PolicyDefinition> policies = List.of(
                policy("verify-key", "VerifyAPIKey", Map.of()),
                policy("quota", "quota", Map.of("count", 100, "timeUnit", "MINUTE")),
                policy("cors", "CORS", Map.of("allowOrigins", List.of("*"))));
        ProxyFlows flows = new ProxyFlows(
                new FlowPair(List.of(new FlowStep("cors", null), new FlowStep("verify-key", null)), List.of()),
                List.of(new ConditionalFlow("writes", "request.verb != \"GET\"",
                        List.of(new FlowStep("quota", null)), List.of())),
                null, null, null);

        assertThat(BundleValidator.validateProxy(policies, flows)).isEmpty();
    }

    @Test
    void reportsEveryProblemWithItsLocation() {
        List<PolicyDefinition> policies = List.of(
                policy("q", "Quota", Map.of("cout", 10)),
                policy("q", "Nope", Map.of()),
                policy("bad name!", "CORS", Map.of("allowOrigins", List.of("*"))));
        ProxyFlows flows = new ProxyFlows(new FlowPair(List.of(new FlowStep("missing", "a ==")), null),
                null, null, List.of(new FaultRule("r", "fault.name ==", List.of())), null);

        List<String> problems = BundleValidator.validateProxy(policies, flows);

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("policies[0]").contains("cout"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("duplicate policy name 'q'"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("unknown policy type 'Nope'"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("policies[2].name"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("preFlow.request[0]: unknown policy 'missing'"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("preFlow.request[0].condition"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("faultRules[0].condition"));
    }

    @Test
    void sharedFlowsNeedSteps() {
        assertThat(BundleValidator.validateSharedFlow(List.of(), List.of()))
                .contains("a shared flow needs at least one step");
    }

    @Test
    void findsReferencedSharedFlows() {
        assertThat(BundleValidator.referencedSharedFlows(List.of(
                policy("call-security", "FlowCallout", Map.of("sharedFlow", "security")),
                policy("quota", "Quota", Map.of("count", 1)))))
                .containsExactly("security");
    }

    @Test
    void binderAppliesDefaultsAndRejectsInvalidValues() {
        QuotaConfig quota = (QuotaConfig) PolicyConfigBinder.bind(policy("q", "Quota", Map.of("count", 5)));
        assertThat(quota.timeUnit()).isEqualTo("minute");
        assertThat(quota.failOpen()).isTrue();
        assertThat(quota.window().toSeconds()).isEqualTo(60);

        assertThatThrownBy(() -> PolicyConfigBinder.bind(policy("s", "SpikeArrest", Map.of("rate", "fast"))))
                .isInstanceOf(PolicyConfigException.class).hasMessageContaining("100ps");
        assertThatThrownBy(() -> PolicyConfigBinder.bind(policy("j", "VerifyJWT", Map.of("algorithm", "HS256", "secret", "short"))))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> PolicyConfigBinder.bind(policy("a", "AssignMessage", Map.of("target", "response", "setVerb", "POST"))))
                .hasMessageContaining("only apply to target 'request'");
        assertThatThrownBy(() -> PolicyConfigBinder.bind(policy("c", "AccessControl", Map.of("allow", List.of("example.com")))))
                .hasMessageContaining("example.com");
    }

    @Test
    void bundlesRoundTripThroughJson() {
        List<PolicyDefinition> policies = List.of(policy("cors", "CORS", Map.of("allowOrigins", List.of("*"))));
        ProxyFlows flows = new ProxyFlows(new FlowPair(List.of(new FlowStep("cors", "request.verb == \"GET\"")), null),
                null, null, null, new FaultRule("default", null, List.of()));

        assertThat(BundleJson.readPolicies(BundleJson.writePolicies(policies))).isEqualTo(policies);
        assertThat(BundleJson.readFlows(BundleJson.writeFlows(flows))).isEqualTo(flows);
        assertThat(BundleJson.readFlows("{}")).isEqualTo(ProxyFlows.EMPTY);
    }
}

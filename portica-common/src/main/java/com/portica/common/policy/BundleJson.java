package com.portica.common.policy;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** JSON (de)serialization of bundle parts, e.g. for JSONB columns. */
public final class BundleJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<PolicyDefinition>> POLICIES = new TypeReference<>() {
    };
    private static final TypeReference<List<FlowStep>> STEPS = new TypeReference<>() {
    };

    private BundleJson() {
    }

    public static String writePolicies(List<PolicyDefinition> policies) {
        return MAPPER.writeValueAsString(policies == null ? List.of() : policies);
    }

    public static List<PolicyDefinition> readPolicies(String json) {
        return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, POLICIES);
    }

    public static String writeFlows(ProxyFlows flows) {
        return MAPPER.writeValueAsString(flows == null ? ProxyFlows.EMPTY : flows);
    }

    public static ProxyFlows readFlows(String json) {
        return json == null || json.isBlank() ? ProxyFlows.EMPTY : MAPPER.readValue(json, ProxyFlows.class);
    }

    public static String writeSteps(List<FlowStep> steps) {
        return MAPPER.writeValueAsString(steps == null ? List.of() : steps);
    }

    public static List<FlowStep> readSteps(String json) {
        return json == null || json.isBlank() ? List.of() : MAPPER.readValue(json, STEPS);
    }
}

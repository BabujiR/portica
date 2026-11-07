package com.portica.gateway.policy.engine;

import com.portica.common.policy.condition.VariableResolver;
import com.portica.gateway.policy.MessageContext;

import java.util.List;

/**
 * An API proxy revision ready to execute.
 *
 * @param requiresRequestBody buffer the request body before the request flow
 * @param bufferedResponse    buffer the target response so response policies can read/replace it;
 *                            otherwise response steps run on headers only and the body streams through
 */
public record CompiledProxy(String bundleKey, MessageContext.ProxyInfo info,
                            List<CompiledStep> preRequest, List<CompiledStep> preResponse,
                            List<CompiledFlow> flows,
                            List<CompiledStep> postRequest, List<CompiledStep> postResponse,
                            List<CompiledFaultRule> faultRules, CompiledFaultRule defaultFaultRule,
                            boolean requiresRequestBody, boolean bufferedResponse) {

    /** First conditional flow whose condition matches, or null. */
    public CompiledFlow match(VariableResolver variables) {
        for (CompiledFlow flow : flows) {
            if (flow.condition().test(variables)) {
                return flow;
            }
        }
        return null;
    }

    /** True when no step is attached anywhere: the engine can take its fast path. */
    public boolean hasNoSteps() {
        return preRequest.isEmpty() && preResponse.isEmpty() && postRequest.isEmpty() && postResponse.isEmpty()
                && flows.stream().allMatch(f -> f.request().isEmpty() && f.response().isEmpty())
                && faultRules.stream().allMatch(r -> r.steps().isEmpty())
                && (defaultFaultRule == null || defaultFaultRule.steps().isEmpty());
    }
}

package com.portica.common.policy;

import java.util.List;

/**
 * Execution model of an API proxy revision.
 * <pre>
 * request:  preFlow.request  -> first matching conditionalFlow.request  -> postFlow.request  -> target
 * response: preFlow.response -> same conditionalFlow.response           -> postFlow.response -> client
 * fault:    first matching faultRule (else defaultFaultRule) shapes the error response
 * </pre>
 */
public record ProxyFlows(FlowPair preFlow, List<ConditionalFlow> conditionalFlows, FlowPair postFlow,
                         List<FaultRule> faultRules, FaultRule defaultFaultRule) {

    public static final ProxyFlows EMPTY = new ProxyFlows(null, null, null, null, null);

    public ProxyFlows {
        preFlow = preFlow == null ? FlowPair.EMPTY : preFlow;
        postFlow = postFlow == null ? FlowPair.EMPTY : postFlow;
        conditionalFlows = Lists.copy(conditionalFlows);
        faultRules = Lists.copy(faultRules);
    }
}

package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.FlowCalloutConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.engine.CompiledSharedFlow;
import com.portica.gateway.policy.engine.FlowExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * FlowCallout: runs a shared flow's steps in the caller's context (same request, response and
 * variables). The shared flow is linked when the environment is compiled.
 */
public final class FlowCalloutPolicy implements Policy {

    private final String name;
    private final CompiledSharedFlow sharedFlow;

    public FlowCalloutPolicy(String name, CompiledSharedFlow sharedFlow) {
        this.name = name;
        this.sharedFlow = sharedFlow;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        return FlowExecutor.run(sharedFlow.steps(), ctx);
    }

    @Override
    public boolean requiresRequestBody() {
        return sharedFlow.requiresRequestBody();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return sharedFlow.requiresBufferedResponse(placement);
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.FLOW_CALLOUT;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new FlowCalloutPolicy(name, context.sharedFlow(((FlowCalloutConfig) config).sharedFlow()));
        }
    }
}

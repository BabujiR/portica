package com.portica.gateway.policy.engine;

import com.portica.common.policy.ConditionalFlow;
import com.portica.common.policy.FaultRule;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyConfigBinder;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.PolicyType;
import com.portica.common.policy.ProxyFlows;
import com.portica.common.policy.condition.Condition;
import com.portica.common.policy.condition.ConditionParser;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import com.portica.gateway.runtime.RuntimeModel.RuntimeSharedFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compiles an environment snapshot: instantiates every policy once, parses every condition, links
 * FlowCallouts to shared flows, and works out whether request/response bodies must be buffered.
 * Anything invalid fails that proxy's compilation; it is then not routed (fail closed).
 */
@Component
public class ProxyBundleCompiler {

    private static final Logger log = LoggerFactory.getLogger(ProxyBundleCompiler.class);
    private static final int MAX_SHARED_FLOW_DEPTH = 5;

    private final Map<PolicyType, PolicyFactory> factories;

    public ProxyBundleCompiler(List<PolicyFactory> factories) {
        Map<PolicyType, PolicyFactory> byType = new EnumMap<>(PolicyType.class);
        for (PolicyFactory f : factories) {
            byType.put(f.type(), f);
        }
        this.factories = byType;
    }

    public CompiledEnvironment compile(RuntimeEnvironmentConfig env) {
        SharedFlows sharedFlows = new SharedFlows(env);
        Map<String, CompiledProxy> proxies = new LinkedHashMap<>();
        for (RuntimeProxy proxy : env.proxies()) {
            String key = proxy.bundleKey(env.organization(), env.environment());
            try {
                proxies.put(key, compileProxy(env, proxy, key, sharedFlows));
            } catch (RuntimeException e) {
                log.error("API proxy {} could not be compiled and will not be served: {}", key, e.getMessage());
            }
        }
        return new CompiledEnvironment(Map.copyOf(proxies));
    }

    // ------------------------------------------------------------------ proxies

    private CompiledProxy compileProxy(RuntimeEnvironmentConfig env, RuntimeProxy proxy, String key,
                                       SharedFlows sharedFlows) {
        Map<String, Bound> policies = instantiate(proxy.policies(), sharedFlows.context(new ArrayDeque<>()));
        ProxyFlows f = proxy.flows();
        Flags flags = new Flags();

        List<CompiledStep> preRequest = steps(f.preFlow().request(), policies, PolicyPhase.REQUEST, flags);
        List<CompiledStep> preResponse = steps(f.preFlow().response(), policies, PolicyPhase.RESPONSE, flags);
        List<CompiledFlow> flows = new ArrayList<>();
        for (ConditionalFlow cf : f.conditionalFlows()) {
            Condition condition = condition(cf.condition(), flags, PolicyPhase.REQUEST);
            flows.add(new CompiledFlow(cf.name(), condition,
                    steps(cf.request(), policies, PolicyPhase.REQUEST, flags),
                    steps(cf.response(), policies, PolicyPhase.RESPONSE, flags)));
        }
        List<CompiledStep> postRequest = steps(f.postFlow().request(), policies, PolicyPhase.REQUEST, flags);
        List<CompiledStep> postResponse = steps(f.postFlow().response(), policies, PolicyPhase.RESPONSE, flags);
        List<CompiledFaultRule> faultRules = new ArrayList<>();
        for (FaultRule rule : f.faultRules()) {
            faultRules.add(faultRule(rule, policies));
        }
        CompiledFaultRule defaultRule = f.defaultFaultRule() == null ? null : faultRule(f.defaultFaultRule(), policies);

        MessageContext.ProxyInfo info = new MessageContext.ProxyInfo(env.organization(), env.environment(),
                proxy.name(), proxy.revision(), proxy.basePath());
        return new CompiledProxy(key, info, preRequest, preResponse, List.copyOf(flows), postRequest, postResponse,
                List.copyOf(faultRules), defaultRule, flags.requestBody, flags.bufferedResponse);
    }

    private CompiledFaultRule faultRule(FaultRule rule, Map<String, Bound> policies) {
        // Fault rules work on the error response the engine builds itself: no buffering involved.
        Flags ignored = new Flags();
        return new CompiledFaultRule(rule.name(), ConditionParser.parse(rule.condition()),
                steps(rule.steps(), policies, PolicyPhase.FAULT, ignored));
    }

    // ------------------------------------------------------------------ shared flows

    /** Lazily compiles shared flows on first use, detecting cycles and excessive nesting. */
    private final class SharedFlows {

        private final RuntimeEnvironmentConfig env;
        private final Map<String, RuntimeSharedFlow> definitions;
        private final Map<String, CompiledSharedFlow> compiled = new HashMap<>();

        SharedFlows(RuntimeEnvironmentConfig env) {
            this.env = env;
            this.definitions = env.sharedFlows().stream()
                    .collect(Collectors.toMap(RuntimeSharedFlow::name, Function.identity(), (a, b) -> b));
        }

        PolicyCompileContext context(Deque<String> stack) {
            return new PolicyCompileContext() {
                @Override
                public String organization() {
                    return env.organization();
                }

                @Override
                public String environment() {
                    return env.environment();
                }

                @Override
                public CompiledSharedFlow sharedFlow(String name) {
                    return get(name, stack);
                }

                @Override
                public RuntimeKeyValueMap keyValueMap(String scope, String name) {
                    return env.keyValueMaps().stream()
                            .filter(m -> m.name().equals(name) && m.scope().equals(scope))
                            .findFirst().orElse(null);
                }
            };
        }

        CompiledSharedFlow get(String name, Deque<String> stack) {
            CompiledSharedFlow done = compiled.get(name);
            if (done != null) {
                return done;
            }
            RuntimeSharedFlow def = definitions.get(name);
            if (def == null) {
                throw new IllegalStateException("shared flow '" + name + "' is not deployed in " + env.environment());
            }
            if (stack.contains(name)) {
                throw new IllegalStateException("shared flow cycle: " + String.join(" -> ", stack) + " -> " + name);
            }
            if (stack.size() >= MAX_SHARED_FLOW_DEPTH) {
                throw new IllegalStateException("shared flows are nested more than " + MAX_SHARED_FLOW_DEPTH + " deep");
            }
            stack.push(name);
            try {
                Map<String, Bound> policies = instantiate(def.policies(), context(stack));
                Flags onRequest = new Flags();
                Flags onResponse = new Flags();
                List<CompiledStep> steps = steps(def.steps(), policies, PolicyPhase.REQUEST, onRequest);
                steps(def.steps(), policies, PolicyPhase.RESPONSE, onResponse);
                CompiledSharedFlow result = new CompiledSharedFlow(name, def.revision(), steps,
                        onRequest.requestBody || onResponse.requestBody,
                        onRequest.bufferedResponse, onResponse.bufferedResponse);
                compiled.put(name, result);
                return result;
            } finally {
                stack.pop();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A policy instance plus how its definition wants faults handled. */
    private record Bound(Policy policy, boolean continueOnError, String type) {
    }

    private Map<String, Bound> instantiate(List<PolicyDefinition> definitions, PolicyCompileContext context) {
        Map<String, Bound> policies = new HashMap<>();
        for (PolicyDefinition def : definitions) {
            if (!def.enabledOrDefault()) {
                continue; // steps referring to a disabled policy are dropped below
            }
            PolicyConfig config = PolicyConfigBinder.bind(def);
            PolicyType type = PolicyType.fromTypeName(def.type()).orElseThrow();
            PolicyFactory factory = factories.get(type);
            if (factory == null) {
                throw new IllegalStateException("policy type " + type.typeName() + " is not available in this gateway");
            }
            policies.put(def.name(), new Bound(factory.create(def.name(), config, context), def.continueOnErrorOrDefault(), def.type()));
        }
        return policies;
    }

    private List<CompiledStep> steps(List<FlowStep> steps, Map<String, Bound> policies, PolicyPhase placement,
                                     Flags flags) {
        List<CompiledStep> result = new ArrayList<>();
        for (FlowStep step : steps) {
            Bound bound = policies.get(step.policy());
            if (bound == null) {
                continue; // disabled policy (unknown names are rejected by the management plane)
            }
            Condition condition = condition(step.condition(), flags, placement);
            flags.requestBody |= bound.policy().requiresRequestBody();
            flags.bufferedResponse |= bound.policy().requiresBufferedResponse(placement);
            result.add(new CompiledStep(bound.policy(), condition, bound.continueOnError(), bound.type()));
        }
        return List.copyOf(result);
    }

    private static Condition condition(String text, Flags flags, PolicyPhase placement) {
        Condition condition = ConditionParser.parse(text);
        if (condition.variables().contains("request.content")) {
            flags.requestBody = true;
        }
        if (placement == PolicyPhase.RESPONSE && condition.variables().contains("response.content")) {
            flags.bufferedResponse = true;
        }
        return condition;
    }

    private static final class Flags {
        boolean requestBody;
        boolean bufferedResponse;
    }
}

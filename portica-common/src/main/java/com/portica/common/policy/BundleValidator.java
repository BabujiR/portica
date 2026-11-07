package com.portica.common.policy;

import com.portica.common.policy.condition.ConditionParser;
import com.portica.common.policy.condition.ConditionSyntaxException;
import com.portica.common.policy.config.KeyValueMapOperationsConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates API proxy and shared flow bundles: policy names and configs, step references and
 * every condition. Used by the management plane before a revision is stored.
 */
public final class BundleValidator {

    public static final Pattern POLICY_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    public static final int MAX_POLICIES = 100;
    public static final int MAX_STEPS = 300;

    private BundleValidator() {
    }

    public static List<String> validateProxy(List<PolicyDefinition> policies, ProxyFlows flows) {
        List<String> problems = new ArrayList<>();
        Set<String> names = validatePolicies(policies, problems);
        ProxyFlows f = flows == null ? ProxyFlows.EMPTY : flows;
        int[] stepCount = {0};
        validateSteps(f.preFlow().request(), "preFlow.request", names, problems, stepCount);
        validateSteps(f.preFlow().response(), "preFlow.response", names, problems, stepCount);
        Set<String> flowNames = new HashSet<>();
        for (int i = 0; i < f.conditionalFlows().size(); i++) {
            ConditionalFlow flow = f.conditionalFlows().get(i);
            String where = "conditionalFlows[" + i + "]";
            if (flow == null) {
                problems.add(where + " is null");
                continue;
            }
            if (flow.name() == null || flow.name().isBlank()) {
                problems.add(where + ".name is required");
            } else if (!flowNames.add(flow.name())) {
                problems.add(where + ": duplicate flow name '" + flow.name() + "'");
            }
            condition(flow.condition(), where + ".condition", problems);
            validateSteps(flow.request(), where + ".request", names, problems, stepCount);
            validateSteps(flow.response(), where + ".response", names, problems, stepCount);
        }
        validateSteps(f.postFlow().request(), "postFlow.request", names, problems, stepCount);
        validateSteps(f.postFlow().response(), "postFlow.response", names, problems, stepCount);
        for (int i = 0; i < f.faultRules().size(); i++) {
            FaultRule rule = f.faultRules().get(i);
            String where = "faultRules[" + i + "]";
            if (rule == null) {
                problems.add(where + " is null");
                continue;
            }
            condition(rule.condition(), where + ".condition", problems);
            validateSteps(rule.steps(), where + ".steps", names, problems, stepCount);
        }
        if (f.defaultFaultRule() != null) {
            validateSteps(f.defaultFaultRule().steps(), "defaultFaultRule.steps", names, problems, stepCount);
        }
        if (stepCount[0] > MAX_STEPS) {
            problems.add("at most " + MAX_STEPS + " steps are allowed");
        }
        return problems;
    }

    public static List<String> validateSharedFlow(List<PolicyDefinition> policies, List<FlowStep> steps) {
        List<String> problems = new ArrayList<>();
        Set<String> names = validatePolicies(policies, problems);
        int[] stepCount = {0};
        validateSteps(steps, "steps", names, problems, stepCount);
        if (steps == null || steps.isEmpty()) {
            problems.add("a shared flow needs at least one step");
        }
        return problems;
    }

    /** Names of shared flows called by FlowCallout policies (enabled or not). */
    public static Set<String> referencedSharedFlows(List<PolicyDefinition> policies) {
        Set<String> result = new LinkedHashSet<>();
        if (policies == null) {
            return result;
        }
        for (PolicyDefinition p : policies) {
            if (p != null && PolicyType.fromTypeName(p.type()).orElse(null) == PolicyType.FLOW_CALLOUT
                    && p.config().get("sharedFlow") instanceof String name) {
                result.add(name);
            }
        }
        return result;
    }

    /** A key value map read by a KeyValueMapOperations policy. */
    public record KvmReference(String scope, String name, Set<String> assignTo) {
    }

    /** Key value maps read by KeyValueMapOperations policies (enabled or not), with the variables they set. */
    public static List<KvmReference> referencedKeyValueMaps(List<PolicyDefinition> policies) {
        List<KvmReference> result = new ArrayList<>();
        if (policies == null) {
            return result;
        }
        for (PolicyDefinition p : policies) {
            if (p == null || PolicyType.fromTypeName(p.type()).orElse(null) != PolicyType.KEY_VALUE_MAP_OPERATIONS) {
                continue;
            }
            try {
                var config = (KeyValueMapOperationsConfig) PolicyConfigBinder.bind(p);
                Set<String> targets = new LinkedHashSet<>();
                config.get().forEach(g -> targets.add(g.assignTo()));
                result.add(new KvmReference(config.scope(), config.mapName(), targets));
            } catch (PolicyConfigException e) {
                // invalid configs are reported by validateProxy / validateSharedFlow
            }
        }
        return result;
    }

    private static Set<String> validatePolicies(List<PolicyDefinition> policies, List<String> problems) {
        Set<String> names = new HashSet<>();
        if (policies == null) {
            return names;
        }
        if (policies.size() > MAX_POLICIES) {
            problems.add("at most " + MAX_POLICIES + " policies are allowed");
        }
        for (int i = 0; i < policies.size(); i++) {
            PolicyDefinition p = policies.get(i);
            String where = "policies[" + i + "]";
            if (p == null) {
                problems.add(where + " is null");
                continue;
            }
            if (p.name() == null || !POLICY_NAME.matcher(p.name()).matches()) {
                problems.add(where + ".name must be 1-64 characters: letters, digits, '.', '_' or '-'");
            } else if (!names.add(p.name())) {
                problems.add(where + ": duplicate policy name '" + p.name() + "'");
            }
            try {
                // FlowCallout targets are checked per environment at deploy time.
                PolicyConfigBinder.bind(p);
            } catch (PolicyConfigException e) {
                e.problems().forEach(msg -> problems.add(where + " (" + p.name() + "): " + msg));
            }
        }
        return names;
    }

    private static void validateSteps(List<FlowStep> steps, String where, Set<String> policyNames,
                                      List<String> problems, int[] count) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            count[0]++;
            FlowStep step = steps.get(i);
            String at = where + "[" + i + "]";
            if (step == null || step.policy() == null) {
                problems.add(at + ".policy is required");
                continue;
            }
            if (!policyNames.contains(step.policy())) {
                problems.add(at + ": unknown policy '" + step.policy() + "'");
            }
            condition(step.condition(), at + ".condition", problems);
        }
    }

    private static void condition(String condition, String where, List<String> problems) {
        try {
            ConditionParser.parse(condition);
        } catch (ConditionSyntaxException e) {
            problems.add(where + ": " + e.getMessage());
        }
    }
}

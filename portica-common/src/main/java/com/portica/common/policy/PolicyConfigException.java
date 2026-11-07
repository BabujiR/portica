package com.portica.common.policy;

import java.util.List;

/** A policy definition that cannot be turned into a valid configuration. */
public class PolicyConfigException extends RuntimeException {

    private final List<String> problems;

    public PolicyConfigException(String policyName, List<String> problems) {
        super("Policy '%s': %s".formatted(policyName, String.join("; ", problems)));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

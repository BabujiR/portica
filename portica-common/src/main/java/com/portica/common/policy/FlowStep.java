package com.portica.common.policy;

/**
 * Executes {@code policy} when {@code condition} (optional) is true.
 *
 * @param policy    name of a policy defined in the same bundle
 * @param condition optional condition, e.g. {@code request.verb == "POST"}
 */
public record FlowStep(String policy, String condition) {
}

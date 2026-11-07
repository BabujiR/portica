package com.portica.gateway.policy.engine;

import com.portica.common.policy.condition.Condition;
import com.portica.gateway.policy.Policy;

/** A step ready to run: the policy instance, its compiled condition and error handling. */
public record CompiledStep(Policy policy, Condition condition, boolean continueOnError) {
}

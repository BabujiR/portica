package com.portica.gateway.policy.engine;

import com.portica.common.policy.condition.Condition;

import java.util.List;

public record CompiledFaultRule(String name, Condition condition, List<CompiledStep> steps) {
}

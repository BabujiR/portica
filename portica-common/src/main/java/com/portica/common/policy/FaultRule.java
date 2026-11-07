package com.portica.common.policy;

import java.util.List;

/**
 * Steps that customize the error response when a fault occurs. The first fault rule whose condition
 * matches runs (for example {@code fault.name == "QuotaViolation"}); if none matches, the default
 * fault rule runs.
 */
public record FaultRule(String name, String condition, List<FlowStep> steps) {

    public FaultRule {
        steps = Lists.copy(steps);
    }
}

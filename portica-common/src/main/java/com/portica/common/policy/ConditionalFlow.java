package com.portica.common.policy;

import java.util.List;

/**
 * A named flow that runs between PreFlow and PostFlow when its condition matches.
 * Only the first matching conditional flow runs (in declaration order).
 */
public record ConditionalFlow(String name, String condition, List<FlowStep> request, List<FlowStep> response) {

    public ConditionalFlow {
        request = Lists.copy(request);
        response = Lists.copy(response);
    }
}

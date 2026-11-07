package com.portica.common.policy;

import java.util.List;

/** Steps run on the request path and on the response path of one flow. */
public record FlowPair(List<FlowStep> request, List<FlowStep> response) {

    public static final FlowPair EMPTY = new FlowPair(List.of(), List.of());

    public FlowPair {
        request = Lists.copy(request);
        response = Lists.copy(response);
    }
}

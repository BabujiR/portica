package com.portica.gateway.policy.engine;

import com.portica.gateway.policy.PolicyPhase;

import java.util.List;

/**
 * A deployed shared flow, compiled once per environment snapshot and called by FlowCallout policies.
 *
 * @param requiresRequestBody          any step needs the request body buffered
 * @param bufferedResponseOnRequest    any step needs a buffered response when called from a request flow
 * @param bufferedResponseOnResponse   ... when called from a response flow
 */
public record CompiledSharedFlow(String name, int revision, List<CompiledStep> steps, boolean requiresRequestBody,
                                 boolean bufferedResponseOnRequest, boolean bufferedResponseOnResponse) {

    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return placement == PolicyPhase.RESPONSE ? bufferedResponseOnResponse : bufferedResponseOnRequest;
    }
}

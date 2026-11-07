package com.portica.gateway.policy;

import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import com.portica.gateway.policy.engine.CompiledSharedFlow;

/** What a factory can ask about the environment its policy is being compiled for. */
public interface PolicyCompileContext {

    String organization();

    String environment();

    /** A deployed shared flow of this environment; throws if it isn't deployed or forms a cycle. */
    CompiledSharedFlow sharedFlow(String name);

    /** A key value map readable here ({@code scope} environment or organization), or null if it doesn't exist. */
    RuntimeKeyValueMap keyValueMap(String scope, String name);
}

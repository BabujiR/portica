package com.portica.gateway.policy.engine;

import java.util.Map;

/**
 * Compilation result for one environment snapshot.
 *
 * @param proxies by bundle key ({@code org/env/name@revision}); proxies that failed to compile are absent
 *                (fail closed: an API whose policies can't be enforced is not routed)
 */
public record CompiledEnvironment(Map<String, CompiledProxy> proxies) {
}

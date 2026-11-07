package com.portica.gateway.policy.store;

import java.util.List;
import java.util.Map;

/** A stored response for ResponseCache. */
public record CachedResponse(int status, Map<String, List<String>> headers, byte[] body) {
}

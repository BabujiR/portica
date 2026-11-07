package com.portica.common.policy.config;

import java.util.List;

/**
 * ResponseCache: serves repeated requests from a shared cache (Redis). Attach it to the request flow;
 * it stores the final response automatically after the response flow.
 *
 * @param ttlSeconds            how long entries live (required)
 * @param keyFragments          templates forming the cache key (default verb + URI)
 * @param cacheableStatusCodes  default [200]
 * @param cacheableMethods      default [GET, HEAD]
 * @param skipLookupCondition   when true, always go to the target
 * @param skipPopulateCondition when true, don't store the response
 * @param maxEntryBytes         largest body cached (default 1 MB)
 */
public record ResponseCacheConfig(Long ttlSeconds, List<String> keyFragments, List<Integer> cacheableStatusCodes,
                                  List<String> cacheableMethods, String skipLookupCondition,
                                  String skipPopulateCondition, Integer maxEntryBytes) implements PolicyConfig {

    public ResponseCacheConfig {
        keyFragments = keyFragments == null || keyFragments.isEmpty()
                ? List.of("{request.verb}", "{request.uri}") : List.copyOf(keyFragments);
        cacheableStatusCodes = cacheableStatusCodes == null || cacheableStatusCodes.isEmpty()
                ? List.of(200) : List.copyOf(cacheableStatusCodes);
        cacheableMethods = cacheableMethods == null || cacheableMethods.isEmpty()
                ? List.of("GET", "HEAD") : cacheableMethods.stream().map(m -> m.toUpperCase(java.util.Locale.ROOT)).toList();
        maxEntryBytes = maxEntryBytes == null ? 1_048_576 : maxEntryBytes;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .require(ttlSeconds != null, "'ttlSeconds' is required")
                .range(ttlSeconds, 1, 30L * 86_400, "ttlSeconds")
                .range(maxEntryBytes, 1, 16 * 1_048_576, "maxEntryBytes")
                .condition(skipLookupCondition, "skipLookupCondition")
                .condition(skipPopulateCondition, "skipPopulateCondition")
                .list();
    }
}

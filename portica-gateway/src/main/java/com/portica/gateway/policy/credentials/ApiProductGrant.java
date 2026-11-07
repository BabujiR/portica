package com.portica.gateway.policy.credentials;

import com.portica.common.policy.condition.WildcardPattern;
import com.portica.gateway.runtime.RuntimeModel.RuntimeApiProduct;
import com.portica.gateway.runtime.RuntimeModel.RuntimeQuota;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An API product held by a credential, ready for request-time checks: which proxies (and paths
 * within them) it opens, its scopes and quota.
 */
public final class ApiProductGrant {

    private static final String ANY_PROXY = "*";

    private final String name;
    private final Set<String> proxies;
    private final List<Pattern> paths;
    private final List<String> scopes;
    private final RuntimeQuota quota;
    private final Map<String, String> attributes;

    private ApiProductGrant(String name, Set<String> proxies, List<String> paths, List<String> scopes,
                            RuntimeQuota quota, Map<String, String> attributes) {
        this.name = name;
        this.proxies = Set.copyOf(proxies);
        this.paths = paths.stream().map(WildcardPattern::compile).toList();
        this.scopes = List.copyOf(scopes);
        this.quota = quota;
        this.attributes = Map.copyOf(attributes);
    }

    public static ApiProductGrant from(RuntimeApiProduct p) {
        return new ApiProductGrant(p.name(), Set.copyOf(p.proxies()), p.paths(), p.scopes(), p.quota(), p.attributes());
    }

    /** Development keys: a product of that name opening every proxy. */
    public static ApiProductGrant everyProxy(String name) {
        return new ApiProductGrant(name, Set.of(ANY_PROXY), List.of(), List.of(), null, Map.of());
    }

    /** True when this product grants {@code proxy} at {@code pathSuffix} (the path below the base path). */
    public boolean allows(String proxy, String pathSuffix) {
        if (!proxies.contains(proxy) && !proxies.contains(ANY_PROXY)) {
            return false;
        }
        if (paths.isEmpty()) {
            return true;
        }
        String path = pathSuffix == null || pathSuffix.isEmpty() ? "/" : pathSuffix;
        return paths.stream().anyMatch(p -> p.matcher(path).matches());
    }

    public String name() {
        return name;
    }

    public List<String> scopes() {
        return scopes;
    }

    /** Null when the product has no quota. */
    public RuntimeQuota quota() {
        return quota;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
}

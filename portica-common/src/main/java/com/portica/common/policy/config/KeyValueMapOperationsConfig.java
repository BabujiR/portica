package com.portica.common.policy.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KeyValueMapOperations: reads entries of a key value map into flow variables at runtime. Maps and
 * their entries are managed through the management API (per environment or per organization);
 * the gateway receives them with the environment's configuration.
 *
 * @param mapName name of the key value map
 * @param scope   {@code environment} (default: the map of the environment the proxy runs in) or
 *                {@code organization}
 * @param get     entries to read
 */
public record KeyValueMapOperationsConfig(String mapName, String scope, List<Get> get) implements PolicyConfig {

    public static final String PRIVATE_PREFIX = "private.";
    private static final Set<String> SCOPES = Set.of("environment", "organization");

    /**
     * @param key          template for the entry key, e.g. {@code backend-url} or {@code {request.header.x-tenant}}
     * @param assignTo     variable receiving the value; must start with {@code private.} for encrypted maps
     * @param defaultValue value used when the entry doesn't exist (optional)
     */
    public record Get(String key, String assignTo, String defaultValue) {
    }

    public KeyValueMapOperationsConfig {
        scope = scope == null ? "environment" : scope.toLowerCase(Locale.ROOT);
        get = get == null ? List.of() : List.copyOf(get);
    }

    @Override
    public List<String> validate() {
        Problems problems = new Problems()
                .notBlank(mapName, "mapName")
                .require(mapName == null || mapName.matches(KEY_VALUE_MAP_NAME), "'mapName' is not a valid name")
                .oneOf(scope, SCOPES, "scope")
                .require(!get.isEmpty(), "'get' needs at least one entry");
        for (int i = 0; i < get.size(); i++) {
            Get g = get.get(i);
            problems.require(g != null && g.key() != null && !g.key().isBlank(), "get[" + i + "].key is required")
                    .require(g != null && g.assignTo() != null && g.assignTo().matches(VARIABLE),
                            "get[" + i + "].assignTo must be a variable name such as private.backend.secret");
        }
        return problems.list();
    }

    /** Key value map names: like resource names. */
    public static final String KEY_VALUE_MAP_NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$";

    /** Variables a policy may write. */
    public static final String VARIABLE = "^[A-Za-z_][A-Za-z0-9_.\\-]{0,127}$";
}

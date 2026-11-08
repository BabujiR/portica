package com.portica.aigateway.runtime;

import java.math.BigDecimal;
import java.util.List;

/** The AI gateway's copy of management's runtime AI feed (keep field names in step; unknown ones are ignored). */
public final class AiRuntime {

    private AiRuntime() {
    }

    public record Config(String organization, String environment, long configVersion, List<Provider> providers,
                         List<Proxy> proxies) {

        public Provider provider(String name) {
            return providers == null ? null : providers.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
        }

        public Proxy proxy(String name) {
            return proxies == null ? null : proxies.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** {@code type}: OPENAI or ANTHROPIC. */
    public record Provider(String name, String type, String baseUrl, String apiKey, boolean enabled, List<Model> models) {

        public Model model(String name) {
            return models == null ? null : models.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
        }
    }

    public record Model(String name, BigDecimal inputCostPerMillion, BigDecimal outputCostPerMillion) {
    }

    public record Proxy(String name, List<Route> routes, String defaultRoute, boolean allowProviderModels,
                        TokenQuota tokenQuota, Pii pii, int timeoutSeconds, Integer maxOutputTokens) {

        public Route route(String name) {
            return routes == null ? null : routes.stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
        }
    }

    public record Route(String name, List<Target> targets) {
    }

    public record Target(String provider, String model) {
    }

    public record TokenQuota(long tokens, int interval, String timeUnit) {
    }

    /** {@code action}: OFF, REDACT or BLOCK; {@code types} empty means all. */
    public record Pii(String action, List<String> types, boolean scanResponses) {
    }
}

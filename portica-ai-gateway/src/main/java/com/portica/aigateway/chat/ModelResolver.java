package com.portica.aigateway.chat;

import com.portica.aigateway.runtime.AiRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code model} a client asked for into the provider calls to try, in order:
 * <ol>
 *   <li>a route name of the AI proxy (its targets, in order, are the fallbacks);</li>
 *   <li>{@code provider/model} when the proxy allows provider models and that provider is used by one of
 *       its routes and lists the model;</li>
 *   <li>nothing given: the proxy's default route.</li>
 * </ol>
 * Disabled providers and models a provider doesn't list are skipped.
 */
public final class ModelResolver {

    /** One provider call: the provider, its model and that model's prices. */
    public record Candidate(AiRuntime.Provider provider, String model, AiRuntime.Model pricing) {
    }

    public record Resolution(String alias, List<Candidate> candidates) {
    }

    private ModelResolver() {
    }

    public static Resolution resolve(AiRuntime.Config config, AiRuntime.Proxy proxy, String requested) {
        String name = requested == null || requested.isBlank() ? proxy.defaultRoute() : requested.strip();
        if (name == null || name.isBlank()) {
            throw AiError.invalid("model_required", "Name a model: one of " + routeNames(proxy));
        }
        AiRuntime.Route route = proxy.route(name);
        List<Candidate> candidates = new ArrayList<>();
        if (route != null) {
            for (AiRuntime.Target t : route.targets() == null ? List.<AiRuntime.Target>of() : route.targets()) {
                candidate(config, t.provider(), t.model(), candidates);
            }
            if (candidates.isEmpty()) {
                throw new AiError(503, "api_error", "no_available_provider",
                        "No enabled provider serves route '" + name + "'");
            }
            return new Resolution(name, candidates);
        }
        int slash = name.indexOf('/');
        if (proxy.allowProviderModels() && slash > 0) {
            String provider = name.substring(0, slash);
            String model = name.substring(slash + 1);
            boolean used = proxy.routes() != null && proxy.routes().stream()
                    .flatMap(r -> r.targets().stream()).anyMatch(t -> t.provider().equals(provider));
            if (used) {
                candidate(config, provider, model, candidates);
            }
            if (!candidates.isEmpty()) {
                return new Resolution(name, candidates);
            }
        }
        throw AiError.invalid("model_not_found", "Unknown model '" + name + "'. Available: " + routeNames(proxy));
    }

    private static void candidate(AiRuntime.Config config, String providerName, String model, List<Candidate> out) {
        AiRuntime.Provider provider = config.provider(providerName);
        if (provider == null || !provider.enabled()) {
            return;
        }
        AiRuntime.Model pricing = provider.model(model);
        if (pricing == null) {
            return;
        }
        out.add(new Candidate(provider, model, pricing));
    }

    static List<String> routeNames(AiRuntime.Proxy proxy) {
        return proxy.routes() == null ? List.of() : proxy.routes().stream().map(AiRuntime.Route::name).toList();
    }
}

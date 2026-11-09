package com.portica.management.ai;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.ai.AiDtos.AiModel;
import com.portica.management.ai.AiDtos.AiProviderResponse;
import com.portica.management.ai.AiDtos.AiProviderType;
import com.portica.management.ai.AiDtos.AiProxyResponse;
import com.portica.management.ai.AiDtos.AiProxySpec;
import com.portica.management.ai.AiDtos.AiRoute;
import com.portica.management.ai.AiDtos.AiTarget;
import com.portica.management.ai.AiDtos.ClientAuth;
import com.portica.management.ai.AiDtos.CreateAiProviderRequest;
import com.portica.management.ai.AiDtos.CreateAiProxyRequest;
import com.portica.management.ai.AiDtos.PiiPolicy;
import com.portica.management.ai.AiDtos.UpdateAiProviderRequest;
import com.portica.management.ai.AiDtos.UpdateAiProxyRequest;
import com.portica.management.audit.AuditService;
import com.portica.management.kvm.KvmCipher;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.runtime.RuntimeChanges;
import com.portica.management.shared.GatewayNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM providers and AI proxies. Every change bumps the config version of the organization's
 * environments, so the AI gateway (and the gateway, which fronts AI proxies) pick it up within seconds.
 */
@Service
public class AiService {

    static final String PROVIDER = "AiProvider";
    static final String PROXY = "AiProxy";
    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final OrganizationFinder organizations;
    private final AiProviderRepository providers;
    private final AiProxyRepository proxies;
    private final GatewayNames gatewayNames;
    private final KvmCipher cipher;
    private final RuntimeChanges runtimeChanges;
    private final AuditService audit;

    public AiService(OrganizationFinder organizations, AiProviderRepository providers, AiProxyRepository proxies,
                     GatewayNames gatewayNames, KvmCipher cipher, RuntimeChanges runtimeChanges, AuditService audit) {
        this.organizations = organizations;
        this.providers = providers;
        this.proxies = proxies;
        this.gatewayNames = gatewayNames;
        this.cipher = cipher;
        this.runtimeChanges = runtimeChanges;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ providers

    @Transactional(readOnly = true)
    public List<AiProviderResponse> listProviders(String org) {
        return providers.findByOrganization_IdOrderByName(organizations.getActive(org).getId()).stream()
                .map(AiService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AiProviderResponse getProvider(String org, String name) {
        return toResponse(findProvider(organizations.getActive(org), name));
    }

    @Transactional
    public AiProviderResponse createProvider(String org, CreateAiProviderRequest r) {
        Organization organization = organizations.getActive(org);
        if (providers.findByOrganization_IdAndName(organization.getId(), r.name()).isPresent()) {
            throw ResourceConflictException.alreadyExists(PROVIDER, r.name());
        }
        AiProvider provider = new AiProvider(organization, r.name(), r.type());
        provider.define(r.displayName(), baseUrl(r.type(), r.baseUrl()), models(r.models()), r.enabled() == null || r.enabled());
        setApiKey(provider, r.apiKey());
        providers.save(provider);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "ai-provider.created", PROVIDER, r.name(), providerDetails(provider));
        return toResponse(provider);
    }

    @Transactional
    public AiProviderResponse updateProvider(String org, String name, UpdateAiProviderRequest r) {
        Organization organization = organizations.getActive(org);
        AiProvider provider = findProvider(organization, name);
        List<AiModel> models = models(r.models());
        requireModelsStillCovered(organization, provider.getName(), models);
        provider.define(r.displayName(), baseUrl(provider.getType(), r.baseUrl()), models, r.enabled() == null || r.enabled());
        if (r.apiKey() != null) {
            setApiKey(provider, r.apiKey());
        }
        runtimeChanges.organizationChanged(organization);
        Map<String, Object> details = providerDetails(provider);
        details.put("apiKeyChanged", r.apiKey() != null);
        audit.record(organization.getId(), "ai-provider.updated", PROVIDER, name, details);
        return toResponse(provider);
    }

    @Transactional
    public void deleteProvider(String org, String name) {
        Organization organization = organizations.getActive(org);
        AiProvider provider = findProvider(organization, name);
        List<String> users = proxies.findByOrganization_IdOrderByName(organization.getId()).stream()
                .filter(p -> p.spec().routes().stream().flatMap(rt -> rt.targets().stream())
                        .anyMatch(t -> t.provider().equals(name)))
                .map(AiProxy::getName).toList();
        if (!users.isEmpty()) {
            throw new BusinessRuleViolationException("AI provider '%s' is used by AI proxies %s; change them first"
                    .formatted(name, users));
        }
        providers.delete(provider);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "ai-provider.deleted", PROVIDER, name, Map.of());
    }

    /** Plain API key for the runtime feed. */
    public String apiKey(AiProvider provider) {
        return provider.getApiKeyEncrypted() == null ? null : cipher.decrypt(provider.getApiKeyEncrypted(), aad(provider));
    }

    private void setApiKey(AiProvider provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            provider.storeApiKey(null, null);
            return;
        }
        cipher.requireEnabled();
        String key = apiKey.strip();
        String hint = key.length() >= 12 ? "…" + key.substring(key.length() - 4) : null;
        provider.storeApiKey(cipher.encrypt(key, aad(provider)), hint);
    }

    private static String aad(AiProvider provider) {
        return "ai-provider:" + provider.getOrganization().getId() + ":" + provider.getName();
    }

    static String baseUrl(AiProviderType type, String requested) {
        String url = requested == null || requested.isBlank()
                ? (type == AiProviderType.ANTHROPIC ? "https://api.anthropic.com/v1" : "https://api.openai.com/v1")
                : requested.strip();
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new InvalidRequestException("baseUrl must be an http(s) URL without query or fragment");
            }
        } catch (java.net.URISyntaxException e) {
            throw new InvalidRequestException("baseUrl is not a valid URL");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static List<AiModel> models(List<AiModel> models) {
        Set<String> seen = new HashSet<>();
        for (AiModel m : models) {
            if (!seen.add(m.name())) {
                throw new InvalidRequestException("Model '%s' is listed twice".formatted(m.name()));
            }
        }
        return List.copyOf(models);
    }

    /** Removing a model an AI proxy routes to would break that proxy. */
    private void requireModelsStillCovered(Organization organization, String provider, List<AiModel> models) {
        Set<String> names = models.stream().map(AiModel::name).collect(Collectors.toSet());
        for (AiProxy proxy : proxies.findByOrganization_IdOrderByName(organization.getId())) {
            for (AiRoute route : proxy.spec().routes()) {
                for (AiTarget t : route.targets()) {
                    if (t.provider().equals(provider) && !names.contains(t.model())) {
                        throw new BusinessRuleViolationException("AI proxy '%s' routes '%s' to model '%s'; keep the model or change the proxy"
                                .formatted(proxy.getName(), route.name(), t.model()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ AI proxies

    @Transactional(readOnly = true)
    public List<AiProxyResponse> listProxies(String org) {
        return proxies.findByOrganization_IdOrderByName(organizations.getActive(org).getId()).stream()
                .map(AiService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AiProxyResponse getProxy(String org, String name) {
        return toResponse(findProxy(organizations.getActive(org), name));
    }

    @Transactional
    public AiProxyResponse createProxy(String org, CreateAiProxyRequest r) {
        Organization organization = organizations.getActive(org);
        if (proxies.existsByOrganization_IdAndName(organization.getId(), r.name())) {
            throw ResourceConflictException.alreadyExists(PROXY, r.name());
        }
        gatewayNames.requireFreeGatewayName(organization.getId(), GatewayNames.forAiProxy(r.name()));
        AiProxySpec spec = spec(organization, r.environments(), r.auth(), r.routes(), r.defaultRoute(),
                r.allowProviderModels(), r.tokenQuota(), r.pii(), r.timeoutSeconds(), r.maxOutputTokens());
        AiProxy proxy = new AiProxy(organization, r.name());
        proxy.define(r.displayName(), r.description(), spec);
        proxies.save(proxy);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "ai-proxy.created", PROXY, r.name(), proxyDetails(spec));
        return toResponse(proxy);
    }

    @Transactional
    public AiProxyResponse updateProxy(String org, String name, UpdateAiProxyRequest r) {
        Organization organization = organizations.getActive(org);
        AiProxy proxy = findProxy(organization, name);
        AiProxySpec spec = spec(organization, r.environments(), r.auth(), r.routes(), r.defaultRoute(),
                r.allowProviderModels(), r.tokenQuota(), r.pii(), r.timeoutSeconds(), r.maxOutputTokens());
        proxy.define(r.displayName(), r.description(), spec);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "ai-proxy.updated", PROXY, name, proxyDetails(spec));
        return toResponse(proxy);
    }

    @Transactional
    public void deleteProxy(String org, String name) {
        Organization organization = organizations.getActive(org);
        proxies.delete(findProxy(organization, name));
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "ai-proxy.deleted", PROXY, name, Map.of());
    }

    private AiProxySpec spec(Organization organization, List<String> environments, ClientAuth auth, List<AiRoute> routes,
                             String defaultRoute, Boolean allowProviderModels, AiDtos.TokenQuota tokenQuota,
                             PiiPolicy pii, Integer timeoutSeconds, Integer maxOutputTokens) {
        Map<String, AiProvider> known = providers.findByOrganization_IdOrderByName(organization.getId()).stream()
                .collect(Collectors.toMap(AiProvider::getName, Function.identity()));
        Set<String> names = new HashSet<>();
        for (AiRoute route : routes) {
            if (!names.add(route.name())) {
                throw new InvalidRequestException("Route '%s' is defined twice".formatted(route.name()));
            }
            if (route.name().contains("/")) {
                throw new InvalidRequestException("Route names can't contain '/' (reserved for provider/model)");
            }
            for (AiTarget target : route.targets()) {
                AiProvider provider = known.get(target.provider());
                if (provider == null) {
                    throw new InvalidRequestException("Route '%s' uses unknown AI provider '%s'"
                            .formatted(route.name(), target.provider()));
                }
                if (provider.models().stream().noneMatch(m -> m.name().equals(target.model()))) {
                    throw new InvalidRequestException("AI provider '%s' doesn't list model '%s' (add it with its prices first)"
                            .formatted(target.provider(), target.model()));
                }
            }
        }
        String defaultName = defaultRoute == null || defaultRoute.isBlank() ? routes.getFirst().name() : defaultRoute;
        if (!names.contains(defaultName)) {
            throw new InvalidRequestException("defaultRoute '%s' is not one of the routes".formatted(defaultName));
        }
        List<String> envs = environments == null ? List.of() : environments.stream().distinct().toList();
        return new AiProxySpec(envs, auth == null ? ClientAuth.API_KEY : auth, List.copyOf(routes), defaultName,
                allowProviderModels != null && allowProviderModels, tokenQuota,
                pii == null ? new PiiPolicy(null, null, null) : pii,
                timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds, maxOutputTokens);
    }

    // ------------------------------------------------------------------ helpers

    AiProvider findProvider(Organization organization, String name) {
        return providers.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(PROVIDER, name));
    }

    private AiProxy findProxy(Organization organization, String name) {
        return proxies.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(PROXY, name));
    }

    static AiProviderResponse toResponse(AiProvider p) {
        return new AiProviderResponse(p.getId(), p.getName(), p.getDisplayName(), p.getType(), p.getBaseUrl(),
                p.models(), p.getApiKeyEncrypted() != null, p.getApiKeyHint(), p.isEnabled(),
                p.getCreatedAt(), p.getCreatedBy(), p.getUpdatedAt(), p.getUpdatedBy());
    }

    static AiProxyResponse toResponse(AiProxy p) {
        AiProxySpec s = p.spec();
        return new AiProxyResponse(p.getId(), p.getName(), p.getDisplayName(), p.getDescription(), s.environments(),
                s.auth(), s.routes(), s.defaultRoute(), s.allowProviderModels(), s.tokenQuota(), s.pii(),
                s.timeoutSeconds(), s.maxOutputTokens(), GatewayNames.forAiProxy(p.getName()),
                "/ai/" + p.getName() + "/v1", p.getCreatedAt(), p.getCreatedBy(), p.getUpdatedAt(), p.getUpdatedBy());
    }

    private static Map<String, Object> providerDetails(AiProvider p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", p.getType().name());
        d.put("baseUrl", p.getBaseUrl());
        d.put("models", p.models().stream().map(AiModel::name).toList());
        d.put("enabled", p.isEnabled());
        return d;
    }

    private static Map<String, Object> proxyDetails(AiProxySpec s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("routes", s.routes().stream().map(AiRoute::name).toList());
        d.put("environments", s.environments());
        d.put("auth", s.auth().name());
        d.put("tokenQuota", s.tokenQuota() == null ? null : s.tokenQuota().tokens() + "/" + s.tokenQuota().interval()
                + " " + s.tokenQuota().timeUnit());
        d.put("pii", s.pii().action().name());
        return d;
    }
}

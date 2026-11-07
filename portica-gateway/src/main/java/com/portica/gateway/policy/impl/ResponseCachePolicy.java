package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.condition.Condition;
import com.portica.common.policy.condition.ConditionParser;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.ResponseCacheConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.Headers;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.ProxyResponse;
import com.portica.gateway.policy.store.CacheStore;
import com.portica.gateway.policy.store.CachedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ResponseCache. On a hit the target is skipped and {@code X-Portica-Cache: HIT} is returned; on a miss
 * the final response is stored (when cacheable) after the response flow. Responses with Set-Cookie or
 * {@code Cache-Control: no-store/private} are never stored. Store errors count as misses.
 * Sets {@code responsecache.<name>.cachehit}.
 */
public final class ResponseCachePolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(ResponseCachePolicy.class);
    private static final Set<String> NOT_STORED = Set.of("content-length", "transfer-encoding", "connection",
            "set-cookie", "date", "x-portica-request-id", "x-portica-cache");

    private final String name;
    private final ResponseCacheConfig config;
    private final List<MessageTemplate> keyFragments;
    private final Condition skipLookup;
    private final Condition skipPopulate;
    private final CacheStore cache;

    public ResponseCachePolicy(String name, ResponseCacheConfig config, CacheStore cache) {
        this.name = name;
        this.config = config;
        this.keyFragments = config.keyFragments().stream().map(MessageTemplate::of).toList();
        this.skipLookup = config.skipLookupCondition() == null ? null : ConditionParser.parse(config.skipLookupCondition());
        this.skipPopulate = config.skipPopulateCondition() == null ? null : ConditionParser.parse(config.skipPopulateCondition());
        this.cache = cache;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        if (!config.cacheableMethods().contains(ctx.request().method().name())) {
            return Mono.empty();
        }
        String key = key(ctx);
        String hitVariable = MessageContext.prefix("responsecache", name) + "cachehit";
        ctx.setVariable(hitVariable, false);
        ctx.onResponse(c -> populate(c, key, hitVariable));
        if (skipLookup != null && skipLookup.test(ctx.resolver())) {
            return Mono.empty();
        }
        return cache.get(key)
                .onErrorResume(e -> {
                    log.warn("Response cache lookup failed ({}): {}", name, e.toString());
                    return Mono.empty();
                })
                .doOnNext(hit -> {
                    HttpHeaders headers = new HttpHeaders();
                    hit.headers().forEach((h, values) -> headers.put(h, new ArrayList<>(values)));
                    headers.set("X-Portica-Cache", "HIT");
                    ctx.setVariable(hitVariable, true);
                    ctx.respond(new ProxyResponse(hit.status(), headers, hit.body()));
                })
                .then();
    }

    private Mono<Void> populate(MessageContext ctx, String key, String hitVariable) {
        ProxyResponse response = ctx.response();
        if (Boolean.TRUE.equals(ctx.variable(hitVariable)) || response == null || response.body() == null) {
            return Mono.empty();
        }
        response.headers().set("X-Portica-Cache", "MISS");
        String cacheControl = String.valueOf(response.headers().getFirst(HttpHeaders.CACHE_CONTROL)).toLowerCase(Locale.ROOT);
        boolean cacheable = config.cacheableStatusCodes().contains(response.status())
                && response.body().length <= config.maxEntryBytes()
                && response.headers().getFirst(HttpHeaders.SET_COOKIE) == null
                && !cacheControl.contains("no-store") && !cacheControl.contains("private")
                && (skipPopulate == null || !skipPopulate.test(ctx.resolver()));
        if (!cacheable) {
            return Mono.empty();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Headers.copy(response.headers()).forEach((h, values) -> {
            if (!NOT_STORED.contains(h.toLowerCase(Locale.ROOT))) {
                headers.put(h, List.copyOf(values));
            }
        });
        return cache.put(key, new CachedResponse(response.status(), headers, response.body()),
                        Duration.ofSeconds(config.ttlSeconds()))
                .onErrorResume(e -> {
                    log.warn("Response cache store failed ({}): {}", name, e.toString());
                    return Mono.empty();
                });
    }

    private String key(MessageContext ctx) {
        MessageContext.ProxyInfo p = ctx.proxy();
        StringBuilder sb = new StringBuilder(p.organization()).append('\0').append(p.environment()).append('\0')
                .append(p.proxy()).append('\0').append(name);
        for (MessageTemplate fragment : keyFragments) {
            sb.append('\0').append(ctx.render(fragment));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "rc:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return true; // stores the response body
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final CacheStore cache;

        public Factory(CacheStore cache) {
            this.cache = cache;
        }

        @Override
        public PolicyType type() {
            return PolicyType.RESPONSE_CACHE;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new ResponseCachePolicy(name, (ResponseCacheConfig) config, cache);
        }
    }
}

package com.portica.aigateway.chat;

import com.portica.aigateway.config.AiGatewayProperties;
import com.portica.aigateway.pii.PiiScanner;
import com.portica.aigateway.provider.AnthropicProvider;
import com.portica.aigateway.provider.LlmProvider;
import com.portica.aigateway.provider.LlmProvider.Usage;
import com.portica.aigateway.provider.OpenAiProvider;
import com.portica.aigateway.provider.ProviderException;
import com.portica.aigateway.quota.QuotaWindow;
import com.portica.aigateway.quota.TokenQuotaStore;
import com.portica.aigateway.runtime.AiConfigFeed;
import com.portica.aigateway.runtime.AiRuntime;
import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.analytics.EventSink;
import com.portica.common.http.PorticaHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible endpoints of one AI proxy, called by the API gateway after it verified the client's
 * credential: {@code POST .../v1/chat/completions} and {@code GET .../v1/models}.
 * <ol>
 *   <li>Resolve the requested model to provider calls (route targets in order).</li>
 *   <li>Refuse the call when the app has used up its token quota for the current window (429).</li>
 *   <li>Apply the PII policy to the prompt: redact (placeholders) or block (400).</li>
 *   <li>Call the providers in order, falling back on rate limits, server errors and timeouts.</li>
 *   <li>Count the tokens against the quota, compute the cost, and publish an event to Kafka.</li>
 * </ol>
 */
@Component
public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    static final String PROVIDER_HEADER = "X-Portica-AI-Provider";
    static final String MODEL_HEADER = "X-Portica-AI-Model";
    static final String COST_HEADER = "X-Portica-AI-Cost-USD";
    static final String TOKENS_REMAINING = "X-Portica-AI-Tokens-Remaining";
    static final String TOKENS_RESET = "X-Portica-AI-Tokens-Reset";

    private final AiGatewayProperties properties;
    private final AiConfigFeed feed;
    private final TokenQuotaStore quotas;
    private final EventSink events;
    private final OpenAiProvider openAi;
    private final AnthropicProvider anthropic;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChatHandler(AiGatewayProperties properties, AiConfigFeed feed, TokenQuotaStore quotas, EventSink events,
                       OpenAiProvider openAi, AnthropicProvider anthropic) {
        this.properties = properties;
        this.feed = feed;
        this.quotas = quotas;
        this.events = events;
        this.openAi = openAi;
        this.anthropic = anthropic;
    }

    /** Who is calling and through which AI proxy (from the path and the gateway's context headers). */
    record Call(String org, String env, String proxyName, String app, String developer, String requestId,
                Instant startedAt, long startNanos) {
    }

    // ------------------------------------------------------------------ models

    public Mono<ServerResponse> models(ServerRequest request) {
        try {
            Call call = call(request);
            AiRuntime.Config config = config(call);
            AiRuntime.Proxy proxy = proxy(config, call);
            ArrayNode data = json.createArrayNode();
            for (AiRuntime.Route route : proxy.routes() == null ? List.<AiRuntime.Route>of() : proxy.routes()) {
                data.addObject().put("id", route.name()).put("object", "model").put("created", 0).put("owned_by", "portica");
            }
            if (proxy.allowProviderModels()) {
                proxy.routes().stream().flatMap(r -> r.targets().stream()).map(AiRuntime.Target::provider).distinct()
                        .map(config::provider)
                        .filter(p -> p != null && p.enabled() && p.models() != null)
                        .forEach(p -> p.models().forEach(m -> data.addObject().put("id", p.name() + "/" + m.name())
                                .put("object", "model").put("created", 0).put("owned_by", p.name())));
            }
            ObjectNode body = json.createObjectNode();
            body.put("object", "list");
            body.set("data", data);
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(json.writeValueAsString(body));
        } catch (AiError e) {
            return e.toResponse();
        }
    }

    // ------------------------------------------------------------------ chat completions

    public Mono<ServerResponse> chat(ServerRequest request) {
        Call call;
        AiRuntime.Config config;
        AiRuntime.Proxy proxy;
        try {
            call = call(request);
            config = config(call);
            proxy = proxy(config, call);
        } catch (AiError e) {
            return e.toResponse();
        }
        return request.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(text -> handle(call, config, proxy, text))
                .onErrorResume(AiError.class, AiError::toResponse);
    }

    private Mono<ServerResponse> handle(Call call, AiRuntime.Config config, AiRuntime.Proxy proxy, String text) {
        ObjectNode body = parse(text);
        boolean stream = body.path("stream").asBoolean(false);
        ModelResolver.Resolution resolution = ModelResolver.resolve(config, proxy, body.path("model").asString(null));

        // PII policy on the prompt
        int redactions = 0;
        AiRuntime.Pii pii = proxy.pii();
        if (pii != null && !"OFF".equals(pii.action())) {
            PiiScanner scanner = new PiiScanner(pii.types());
            ObjectNode probe = "BLOCK".equals(pii.action()) ? body.deepCopy() : body;
            PiiScanner.Findings findings = PromptPii.redactMessages(probe, scanner);
            if (findings.any() && "BLOCK".equals(pii.action())) {
                Map<String, Object> event = event(call, proxy, resolution.alias(), null, stream);
                event.put("status", 400);
                event.put("pii_redactions", findings.total());
                event.put("error", "pii_detected");
                publish(call, event);
                throw AiError.invalid("pii_detected", "The prompt contains personal data (" + String.join(", ",
                        findings.types()) + ") and this AI proxy blocks it");
            }
            redactions = findings.total();
        }
        // a proxy-wide cap on output tokens
        if (proxy.maxOutputTokens() != null) {
            // keep the field the client used (newer OpenAI models only accept max_completion_tokens)
            long cap = proxy.maxOutputTokens();
            String field = body.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens";
            body.put(field, Math.min(body.path(field).asLong(cap), cap));
        }
        int piiRedactions = redactions;
        return checkQuota(call, proxy).flatMap(quota -> stream
                ? stream(call, proxy, resolution, body, piiRedactions, quota)
                : complete(call, proxy, resolution, body, piiRedactions, quota));
    }

    // ------------------------------------------------------------------ non-streaming

    private Mono<ServerResponse> complete(Call call, AiRuntime.Proxy proxy, ModelResolver.Resolution resolution,
                                          ObjectNode body, int piiRedactions, QuotaState quota) {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ModelResolver.Candidate> used = new AtomicReference<>();
        Duration timeout = Duration.ofSeconds(Math.max(5, proxy.timeoutSeconds()));
        return attempt(resolution.candidates(), 0, attempts, used,
                        c -> provider(c).complete(c.provider(), c.model(), body, timeout))
                .flatMap(completion -> {
                    ModelResolver.Candidate c = used.get();
                    Usage usage = completion.usage();
                    BigDecimal cost = Costs.of(usage, c.pricing());
                    int redactions = piiRedactions;
                    AiRuntime.Pii pii = proxy.pii();
                    if (pii != null && "REDACT".equals(pii.action()) && pii.scanResponses()) {
                        redactions += PromptPii.redactCompletion(completion.body(), new PiiScanner(pii.types())).total();
                    }
                    int total = redactions;
                    return consume(call, quota, usage).flatMap(remaining -> {
                        Map<String, Object> event = event(call, proxy, resolution.alias(), c, false);
                        event.put("status", 200);
                        event.put("first_token_ms", elapsedMs(call));
                        fill(event, usage, cost, attempts.get(), total, "");
                        publish(call, event);
                        ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
                        headers(ok, c, cost, quota, remaining);
                        return ok.bodyValue(json.writeValueAsString(completion.body()));
                    });
                })
                .onErrorResume(e -> !(e instanceof AiError), e -> failed(call, proxy, resolution, used.get(), attempts.get(),
                        piiRedactions, false, e));
    }

    // ------------------------------------------------------------------ streaming

    private Mono<ServerResponse> stream(Call call, AiRuntime.Proxy proxy, ModelResolver.Resolution resolution,
                                        ObjectNode body, int piiRedactions, QuotaState quota) {
        boolean clientWantsUsage = body.path("stream_options").path("include_usage").asBoolean(false);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ModelResolver.Candidate> used = new AtomicReference<>();
        Duration timeout = Duration.ofSeconds(Math.max(5, proxy.timeoutSeconds()));
        return attempt(resolution.candidates(), 0, attempts, used,
                        c -> provider(c).stream(c.provider(), c.model(), body, timeout))
                .flatMap(chunks -> {
                    ModelResolver.Candidate c = used.get();
                    AtomicReference<Usage> usage = new AtomicReference<>(Usage.NONE);
                    AtomicLong firstToken = new AtomicLong(-1);
                    AtomicReference<String> error = new AtomicReference<>("");
                    AtomicBoolean finished = new AtomicBoolean();
                    Runnable finish = () -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        Usage u = usage.get();
                        BigDecimal cost = Costs.of(u, c.pricing());
                        consume(call, quota, u).onErrorResume(e -> Mono.just(-1L)).subscribe(remaining -> {
                            Map<String, Object> event = event(call, proxy, resolution.alias(), c, true);
                            event.put("status", error.get().isEmpty() ? 200
                                    : "client_cancelled".equals(error.get()) ? 499 : 502);
                            event.put("first_token_ms", Math.max(0, firstToken.get()));
                            fill(event, u, cost, attempts.get(), piiRedactions, error.get());
                            publish(call, event);
                        });
                    };
                    Flux<ServerSentEvent<String>> events = chunks
                            .doOnNext(chunk -> {
                                if (firstToken.get() < 0 && chunk.path("choices").size() > 0) {
                                    firstToken.set(elapsedMs(call));
                                }
                                if (chunk.path("usage").isObject() && !chunk.path("usage").isEmpty()) {
                                    usage.set(new Usage(chunk.path("usage").path("prompt_tokens").asLong(0),
                                            chunk.path("usage").path("completion_tokens").asLong(0)));
                                }
                            })
                            // the usage-only chunk is ours to count; pass it on only when the client asked for it
                            .filter(chunk -> clientWantsUsage || chunk.path("choices").size() > 0)
                            .map(chunk -> ServerSentEvent.builder(json.writeValueAsString(chunk)).build())
                            .onErrorResume(e -> {
                                error.set(e instanceof ProviderException p ? p.type() : "stream_error");
                                log.warn("AI stream {} via {} failed: {}", call.requestId(), c.provider().name(), e.toString());
                                AiError err = new AiError(502, "api_error", "provider_stream_error",
                                        "The provider stream failed: " + e.getMessage());
                                return Flux.just(ServerSentEvent.builder(json.writeValueAsString(err.body())).build());
                            })
                            .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()))
                            .doOnComplete(finish)
                            .doOnCancel(() -> {
                                error.compareAndSet("", "client_cancelled");
                                finish.run();
                            });
                    ServerResponse.BodyBuilder ok = ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                            .header("Cache-Control", "no-cache");
                    ok.header(PROVIDER_HEADER, c.provider().name()).header(MODEL_HEADER, c.model());
                    if (quota.limited()) {
                        ok.header(TOKENS_RESET, quota.window().resets().toString());
                    }
                    return ok.body(BodyInserters.fromServerSentEvents(events));
                })
                .onErrorResume(e -> !(e instanceof AiError), e -> failed(call, proxy, resolution, used.get(), attempts.get(),
                        piiRedactions, true, e));
    }

    // ------------------------------------------------------------------ provider calls with fallback

    private <T> Mono<T> attempt(List<ModelResolver.Candidate> candidates, int index, AtomicInteger attempts,
                                AtomicReference<ModelResolver.Candidate> used,
                                java.util.function.Function<ModelResolver.Candidate, Mono<T>> call) {
        ModelResolver.Candidate c = candidates.get(index);
        return Mono.defer(() -> {
                    attempts.incrementAndGet();
                    used.set(c);
                    return call.apply(c);
                })
                .onErrorResume(e -> {
                    if (index + 1 < candidates.size() && retryable(e)) {
                        log.info("AI call to {}/{} failed ({}); trying {}", c.provider().name(), c.model(), e.getMessage(),
                                candidates.get(index + 1).provider().name());
                        return attempt(candidates, index + 1, attempts, used, call);
                    }
                    return Mono.error(e);
                });
    }

    static boolean retryable(Throwable e) {
        if (e instanceof ProviderException p) {
            return p.retryable();
        }
        return e instanceof TimeoutException || e instanceof WebClientRequestException;
    }

    private LlmProvider provider(ModelResolver.Candidate c) {
        return "ANTHROPIC".equals(c.provider().type()) ? anthropic : openAi;
    }

    private Mono<ServerResponse> failed(Call call, AiRuntime.Proxy proxy, ModelResolver.Resolution resolution,
                                        ModelResolver.Candidate last, int attempts, int piiRedactions, boolean stream,
                                        Throwable e) {
        AiError error;
        if (e instanceof ProviderException p) {
            int status = p.status() == 429 ? 429 : p.status() >= 500 || p.status() == 401 || p.status() == 403 ? 502
                    : p.status() >= 400 ? p.status() : 502;
            String type = p.status() == 429 ? "rate_limit_error" : status == 502 ? "api_error" : "invalid_request_error";
            error = new AiError(status, type, p.type(), "Provider " + (last == null ? "" : last.provider().name() + " ")
                    + "answered: " + p.getMessage());
        } else if (e instanceof TimeoutException) {
            error = new AiError(504, "api_error", "provider_timeout", "The provider didn't answer in time");
        } else {
            log.warn("AI call {} failed: {}", call.requestId(), e.toString());
            error = new AiError(502, "api_error", "provider_unreachable", "The provider couldn't be reached");
        }
        Map<String, Object> event = event(call, proxy, resolution.alias(), last, stream);
        event.put("status", error.status());
        fill(event, Usage.NONE, BigDecimal.ZERO, attempts, piiRedactions, error.code());
        publish(call, event);
        return error.toResponse();
    }

    // ------------------------------------------------------------------ token quota

    /** Quota state for this call: the counter key and window, or unlimited. */
    record QuotaState(String key, long limit, QuotaWindow window) {

        static final QuotaState NONE = new QuotaState(null, 0, null);

        boolean limited() {
            return key != null;
        }
    }

    private Mono<QuotaState> checkQuota(Call call, AiRuntime.Proxy proxy) {
        AiRuntime.TokenQuota q = proxy.tokenQuota();
        if (q == null || q.tokens() <= 0) {
            return Mono.just(QuotaState.NONE);
        }
        Instant now = Instant.now();
        QuotaWindow window = QuotaWindow.of(q.timeUnit(), q.interval(), now);
        String key = "ai-tokens:" + call.org() + ":" + call.env() + ":" + call.proxyName() + ":"
                + (call.app().isEmpty() ? "-" : call.app()) + ":" + q.timeUnit() + q.interval() + ":" + window.id();
        QuotaState state = new QuotaState(key, q.tokens(), window);
        return quotas.get(key).flatMap(used -> {
            if (used >= q.tokens()) {
                Map<String, Object> event = event(call, proxy, "", null, false);
                event.put("status", 429);
                event.put("error", "token_quota_exceeded");
                publish(call, event);
                return Mono.error(new AiError(429, "rate_limit_error", "token_quota_exceeded",
                        "Token quota of " + q.tokens() + " per " + (q.interval() == 1 ? "" : q.interval() + " ")
                                + q.timeUnit() + " used up; it resets at " + window.resets())
                        .header(TOKENS_REMAINING, "0")
                        .header(TOKENS_RESET, window.resets().toString())
                        .header("Retry-After", String.valueOf(Math.max(1, Duration.between(Instant.now(), window.resets()).toSeconds()))));
            }
            return Mono.just(state);
        }).onErrorResume(e -> !(e instanceof AiError), e -> {
            // the counter store is down: serve the call rather than fail it, and say so
            log.warn("Token quota store unavailable, not enforcing quota for {}: {}", call.requestId(), e.toString());
            return Mono.just(state);
        });
    }

    /** Adds the call's tokens; returns the tokens left in the window (or -1 when unlimited). */
    private Mono<Long> consume(Call call, QuotaState quota, Usage usage) {
        if (!quota.limited()) {
            return Mono.just(-1L);
        }
        return quotas.add(quota.key(), usage.total(), quota.window().ttl(Instant.now()))
                .map(total -> Math.max(0, quota.limit() - total))
                .onErrorResume(e -> {
                    log.warn("Couldn't count tokens for {}: {}", call.requestId(), e.toString());
                    return Mono.just(-1L);
                });
    }

    // ------------------------------------------------------------------ helpers

    private void headers(ServerResponse.BodyBuilder ok, ModelResolver.Candidate c, BigDecimal cost, QuotaState quota,
                         long remaining) {
        ok.header(PROVIDER_HEADER, c.provider().name());
        ok.header(MODEL_HEADER, c.model());
        ok.header(COST_HEADER, cost.toPlainString());
        if (quota.limited() && remaining >= 0) {
            ok.header(TOKENS_REMAINING, Long.toString(remaining));
            ok.header(TOKENS_RESET, quota.window().resets().toString());
        }
    }

    private Call call(ServerRequest request) {
        String expected = properties.internalToken();
        if (expected != null && !expected.isBlank()) {
            String given = request.headers().firstHeader(PorticaHeaders.INTERNAL_TOKEN);
            if (given == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    given.getBytes(StandardCharsets.UTF_8))) {
                throw new AiError(401, "authentication_error", "not_via_gateway",
                        "Call the AI proxy through the Portica gateway");
            }
        }
        String requestId = request.headers().firstHeader(PorticaHeaders.REQUEST_ID);
        return new Call(request.pathVariable("org"), request.pathVariable("env"), request.pathVariable("proxy"),
                header(request, PorticaHeaders.APP), header(request, PorticaHeaders.DEVELOPER),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                Instant.now(), System.nanoTime());
    }

    private static String header(ServerRequest request, String name) {
        String v = request.headers().firstHeader(name);
        return v == null ? "" : v.strip();
    }

    private AiRuntime.Config config(Call call) {
        return feed.get(call.org(), call.env())
                .orElseThrow(() -> AiError.notFound("No AI configuration for " + call.org() + "/" + call.env() + " yet"));
    }

    private static AiRuntime.Proxy proxy(AiRuntime.Config config, Call call) {
        AiRuntime.Proxy proxy = config.proxy(call.proxyName());
        if (proxy == null) {
            throw AiError.notFound("AI proxy '" + call.proxyName() + "' isn't served in " + call.org() + "/" + call.env());
        }
        return proxy;
    }

    private ObjectNode parse(String text) {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (RuntimeException e) {
            throw AiError.invalid("invalid_json", "The request body must be a JSON object");
        }
        if (!(node instanceof ObjectNode body)) {
            throw AiError.invalid("invalid_json", "The request body must be a JSON object");
        }
        if (!body.path("messages").isArray() || body.path("messages").isEmpty()) {
            throw AiError.invalid("messages_required", "'messages' must be a non-empty array");
        }
        return body;
    }

    private static long elapsedMs(Call call) {
        return (System.nanoTime() - call.startNanos()) / 1_000_000;
    }

    private Map<String, Object> event(Call call, AiRuntime.Proxy proxy, String alias, ModelResolver.Candidate c,
                                      boolean stream) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_time", call.startedAt().toString());
        e.put("request_id", call.requestId());
        e.put("organization", call.org());
        e.put("environment", call.env());
        e.put("ai_proxy", proxy.name());
        e.put("model_alias", alias == null ? "" : alias);
        e.put("provider", c == null ? "" : c.provider().name());
        e.put("model", c == null ? "" : c.model());
        e.put("app", call.app());
        e.put("developer", call.developer());
        e.put("streamed", stream ? 1 : 0);
        e.put("total_ms", 0L);
        e.put("first_token_ms", 0L);
        e.put("prompt_tokens", 0L);
        e.put("completion_tokens", 0L);
        e.put("total_tokens", 0L);
        e.put("cost_usd", 0.0);
        e.put("attempts", 0);
        e.put("pii_redactions", 0);
        e.put("error", "");
        return e;
    }

    private static void fill(Map<String, Object> event, Usage usage, BigDecimal cost, int attempts, int redactions,
                             String error) {
        event.put("prompt_tokens", usage.promptTokens());
        event.put("completion_tokens", usage.completionTokens());
        event.put("total_tokens", usage.total());
        event.put("cost_usd", cost.doubleValue());
        event.put("attempts", attempts);
        event.put("pii_redactions", redactions);
        event.put("error", error == null ? "" : error);
    }

    private void publish(Call call, Map<String, Object> event) {
        event.put("total_ms", elapsedMs(call));
        try {
            events.publish(AnalyticsTopics.AI_REQUESTS, call.requestId(), event);
        } catch (RuntimeException e) {
            log.debug("AI event dropped: {}", e.toString());
        }
    }
}

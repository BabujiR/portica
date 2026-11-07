package com.portica.gateway.policy.engine;

import com.portica.common.http.PorticaHeaders;
import com.portica.gateway.config.PolicyEngineProperties;
import com.portica.gateway.policy.Headers;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.ProxyRequest;
import com.portica.gateway.routing.PreflightPassThroughWebFilter;
import com.portica.gateway.policy.ProxyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.addOriginalRequestUrl;

/**
 * Runs a compiled API proxy for one exchange:
 * <ol>
 *   <li>buffer the request body if any policy needs it;</li>
 *   <li>request flow: PreFlow, first matching conditional flow, PostFlow;</li>
 *   <li>forward to the target (unless a policy short-circuited) with the request changes applied;</li>
 *   <li>response flow, on a buffered body or on headers only, then response callbacks;</li>
 *   <li>on any fault: fault rules shape the error response.</li>
 * </ol>
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);
    private static final String MATCHED_FLOW = "portica.matchedFlow";

    private final PolicyEngineProperties properties;

    public PolicyEngine(PolicyEngineProperties properties) {
        this.properties = properties;
    }

    public Mono<Void> handle(ServerWebExchange exchange, GatewayFilterChain chain, CompiledProxy proxy,
                             String gatewayPrefix, String targetPath) {
        MessageContext ctx = newContext(exchange, proxy, gatewayPrefix);
        Mono<Void> loadBody = proxy.requiresRequestBody() ? readRequestBody(exchange.getRequest(), ctx) : Mono.empty();
        return loadBody
                .then(Mono.defer(() -> runRequestFlow(proxy, ctx)))
                .then(Mono.defer(() -> ctx.isShortCircuited()
                        ? respondDirectly(exchange, proxy, ctx)
                        : forward(exchange, chain, proxy, ctx, targetPath)))
                .onErrorResume(PolicyFault.class, fault -> handleFault(exchange, proxy, ctx, fault));
    }

    // ------------------------------------------------------------------ flows

    Mono<Void> runRequestFlow(CompiledProxy proxy, MessageContext ctx) {
        return FlowExecutor.run(proxy.preRequest(), ctx)
                .then(Mono.defer(() -> {
                    CompiledFlow flow = proxy.match(ctx.resolver());
                    if (flow == null) {
                        return Mono.empty();
                    }
                    ctx.attributes().put(MATCHED_FLOW, flow);
                    ctx.setVariable("current.flow.name", flow.name());
                    return FlowExecutor.run(flow.request(), ctx);
                }))
                .then(Mono.defer(() -> FlowExecutor.run(proxy.postRequest(), ctx)));
    }

    Mono<Void> runResponseFlow(CompiledProxy proxy, MessageContext ctx) {
        return FlowExecutor.run(proxy.preResponse(), ctx)
                .then(Mono.defer(() -> {
                    CompiledFlow flow = (CompiledFlow) ctx.attributes().get(MATCHED_FLOW);
                    return flow == null ? Mono.empty() : FlowExecutor.run(flow.response(), ctx);
                }))
                .then(Mono.defer(() -> FlowExecutor.run(proxy.postResponse(), ctx)))
                .then(Mono.defer(() -> Flux.fromIterable(ctx.responseCallbacks())
                        .concatMap(callback -> callback.apply(ctx)).then()));
    }

    private Mono<Void> runFaultRules(CompiledProxy proxy, MessageContext ctx) {
        for (CompiledFaultRule rule : proxy.faultRules()) {
            if (rule.condition().test(ctx.resolver())) {
                return FlowExecutor.run(rule.steps(), ctx);
            }
        }
        return proxy.defaultFaultRule() == null ? Mono.empty() : FlowExecutor.run(proxy.defaultFaultRule().steps(), ctx);
    }

    // ------------------------------------------------------------------ request side

    private MessageContext newContext(ServerWebExchange exchange, CompiledProxy proxy, String gatewayPrefix) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        String suffix = rawPath.length() > gatewayPrefix.length() ? rawPath.substring(gatewayPrefix.length()) : "";
        HttpHeaders headers = Headers.copy(request.getHeaders());
        PreflightPassThroughWebFilter.restore(headers);
        ProxyRequest proxyRequest = new ProxyRequest(request.getMethod(), rawPath, request.getURI().getRawQuery(),
                suffix, headers);
        String requestId = request.getHeaders().getFirst(PorticaHeaders.REQUEST_ID);
        return new MessageContext(requestId, proxy.info(), proxyRequest, clientIp(request));
    }

    private String clientIp(ServerHttpRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].strip();
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    private Mono<Void> readRequestBody(ServerHttpRequest request, MessageContext ctx) {
        return DataBufferUtils.join(request.getBody(), properties.maxRequestBodyBytes())
                .map(PolicyEngine::toBytes)
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, e -> PolicyFault.of("RequestTooLarge", 413,
                        "Request body is larger than " + properties.maxRequestBodyBytes() + " bytes"))
                .doOnNext(bytes -> ctx.request().initBody(bytes))
                .then();
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, CompiledProxy proxy,
                               MessageContext ctx, String targetPath) {
        ServerHttpRequest original = exchange.getRequest();
        addOriginalRequestUrl(exchange, original.getURI());
        ProxyRequest req = ctx.request();

        HttpHeaders headers = Headers.copy(req.headers());
        MessageContext.ProxyInfo info = proxy.info();
        headers.set(PorticaHeaders.ORGANIZATION, info.organization());
        headers.set(PorticaHeaders.ENVIRONMENT, info.environment());
        headers.set(PorticaHeaders.PROXY, info.proxy());
        headers.set(PorticaHeaders.REVISION, Integer.toString(info.revision()));
        if (proxy.bufferedResponse()) {
            headers.remove(HttpHeaders.ACCEPT_ENCODING); // response policies need an uncompressed body
        }
        byte[] body = req.body();
        if (body != null) {
            Headers.removeTransportHeaders(headers);
            headers.setContentLength(body.length);
        }

        String path = targetPath + req.pathSuffix();
        URI uri;
        try {
            uri = UriComponentsBuilder.fromUri(original.getURI())
                    .replacePath(path.isEmpty() ? "/" : path)
                    .replaceQuery(req.forwardedRawQuery())
                    .build(true)
                    .toUri();
        } catch (IllegalArgumentException e) {
            return Mono.error(PolicyFault.of("InvalidTargetPath", 500,
                    "The path or query set by a policy is not a valid URI"));
        }
        ServerHttpRequest mutated = original.mutate()
                .method(req.method())
                .uri(uri)
                .headers(h -> Headers.replaceAll(h, headers))
                .build();
        if (body != null) {
            mutated = new ServerHttpRequestDecorator(mutated) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
                }
            };
        }

        ServerHttpResponse response = exchange.getResponse();
        ServerWebExchange.Builder next = exchange.mutate().request(mutated);
        if (proxy.bufferedResponse()) {
            next.response(new BufferingResponse(response, proxy, ctx));
        } else if (!proxy.hasNoSteps()) {
            response.beforeCommit(() -> headerOnlyResponse(response, proxy, ctx));
        }
        return chain.filter(next.build()).onErrorMap(e -> !(e instanceof PolicyFault), PolicyEngine::targetFault);
    }

    // ------------------------------------------------------------------ response side

    /** Response steps on status and headers only; the body streams through untouched. */
    private Mono<Void> headerOnlyResponse(ServerHttpResponse response, CompiledProxy proxy, MessageContext ctx) {
        if (ctx.phase() == PolicyPhase.FAULT) {
            return Mono.empty(); // our own error response; handled in handleFault
        }
        ctx.beginResponse(new ProxyResponse(status(response), Headers.copy(response.getHeaders()), null));
        return runResponseFlow(proxy, ctx)
                .then(Mono.fromRunnable(() -> {
                    response.setStatusCode(HttpStatusCode.valueOf(ctx.response().status()));
                    Headers.replaceAll(response.getHeaders(), ctx.response().headers());
                    Headers.setAll(response.getHeaders(), ctx.pendingResponseHeaders());
                }))
                .onErrorResume(e -> {
                    // Can't happen by construction (header-only policies don't fault); never break the response.
                    log.error("Header-only response flow failed for {} (request {})", proxy.bundleKey(), ctx.requestId(), e);
                    return Mono.empty();
                })
                .then();
    }

    /** Buffers the target response, runs the response flow on it, then writes the result. */
    private final class BufferingResponse extends ServerHttpResponseDecorator {

        private final CompiledProxy proxy;
        private final MessageContext ctx;
        private boolean processed;

        BufferingResponse(ServerHttpResponse delegate, CompiledProxy proxy, MessageContext ctx) {
            super(delegate);
            this.proxy = proxy;
            this.ctx = ctx;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body, properties.maxResponseBodyBytes())
                    .map(PolicyEngine::toBytes)
                    .defaultIfEmpty(new byte[0])
                    .onErrorMap(DataBufferLimitException.class, e -> PolicyFault.of("TargetResponseTooLarge", 502,
                            "Target response is larger than " + properties.maxResponseBodyBytes() + " bytes"))
                    .flatMap(this::process);
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(p -> Flux.<DataBuffer>from(p)));
        }

        @Override
        public Mono<Void> setComplete() {
            return processed ? getDelegate().setComplete() : process(new byte[0]);
        }

        private Mono<Void> process(byte[] bytes) {
            processed = true;
            ServerHttpResponse delegate = getDelegate();
            ctx.beginResponse(new ProxyResponse(status(delegate), Headers.copy(delegate.getHeaders()), bytes));
            return runResponseFlow(proxy, ctx).then(Mono.defer(() -> write(delegate, ctx)));
        }
    }

    // ------------------------------------------------------------------ short-circuit and faults

    private Mono<Void> respondDirectly(ServerWebExchange exchange, CompiledProxy proxy, MessageContext ctx) {
        ctx.beginResponse(ctx.response());
        return runResponseFlow(proxy, ctx).then(Mono.defer(() -> write(exchange.getResponse(), ctx)));
    }

    private Mono<Void> handleFault(ServerWebExchange exchange, CompiledProxy proxy, MessageContext ctx,
                                   PolicyFault fault) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("Fault {} after the response was committed (request {})", fault.faultName(), ctx.requestId());
            return Mono.empty();
        }
        ctx.enterFault(fault);
        return runFaultRules(proxy, ctx)
                .onErrorResume(e -> {
                    log.warn("Fault rule failed for {} (request {}): {}", proxy.bundleKey(), ctx.requestId(), e.toString());
                    return Mono.empty();
                })
                .then(Mono.defer(() -> write(response, ctx)));
    }

    /** Writes {@code ctx.response()} (plus pending headers) as the final response. */
    private static Mono<Void> write(ServerHttpResponse response, MessageContext ctx) {
        ProxyResponse r = ctx.response();
        response.setStatusCode(HttpStatusCode.valueOf(r.status()));
        HttpHeaders headers = Headers.copy(r.headers());
        Headers.removeTransportHeaders(headers);
        Headers.setAll(headers, ctx.pendingResponseHeaders());
        byte[] body = r.body() == null ? new byte[0] : r.body();
        headers.setContentLength(body.length);
        headers.set(PorticaHeaders.REQUEST_ID, ctx.requestId() == null ? "" : ctx.requestId());
        Headers.replaceAll(response.getHeaders(), headers);
        if (body.length == 0) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static PolicyFault targetFault(Throwable e) {
        if (e instanceof TimeoutException
                || (e instanceof ResponseStatusException rse && rse.getStatusCode().value() == 504)) {
            return PolicyFault.of("TargetTimeout", 504, "The target did not respond in time");
        }
        log.warn("Target call failed: {}", e.toString());
        return PolicyFault.of("TargetUnavailable", 502, "The target could not be reached");
    }

    private static int status(ServerHttpResponse response) {
        HttpStatusCode code = response.getStatusCode();
        return code == null ? 200 : code.value();
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}

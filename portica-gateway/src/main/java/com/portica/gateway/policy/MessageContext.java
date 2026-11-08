package com.portica.gateway.policy;

import com.portica.gateway.trace.TraceRecorder;
import java.util.Collections;
import com.portica.common.policy.condition.VariableResolver;
import com.portica.common.policy.template.MessageTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything policies share while one request passes through an API proxy: the request, the
 * response, flow variables and the current phase. One instance per request; the reactive chain
 * runs its steps sequentially, so no synchronization is needed.
 * <p>
 * Built-in variables (read-only): {@code organization.name, environment.name, apiproxy.name,
 * apiproxy.revision, proxy.basepath, proxy.pathsuffix, request.verb, request.uri, request.path,
 * request.querystring, request.header.<name>, request.queryparam.<name>, request.content,
 * client.ip, messageid, system.timestamp, response.status.code, response.header.<name>,
 * response.content, fault.name, fault.message, error.status.code, current.flow.name}.
 * Policies add their own (e.g. {@code verifyapikey.<policy>.app.name}).
 */
public final class MessageContext {

    /** Identity of the deployed proxy handling the request. */
    public record ProxyInfo(String organization, String environment, String proxy, int revision, String basePath) {
    }

    private final String requestId;
    private final ProxyInfo proxy;
    private final ProxyRequest request;
    private final String clientIp;
    private final Map<String, Object> variables = new HashMap<>();
    private final HttpHeaders pendingResponseHeaders = new HttpHeaders();
    private final List<Function<MessageContext, Mono<Void>>> responseCallbacks = new ArrayList<>();
    private final Map<String, Object> attributes = new HashMap<>();

    private ProxyResponse response;
    private PolicyPhase phase = PolicyPhase.REQUEST;
    private boolean shortCircuited;
    private PolicyFault fault;
    private TraceRecorder trace;

    public MessageContext(String requestId, ProxyInfo proxy, ProxyRequest request, String clientIp) {
        this.requestId = requestId;
        this.proxy = proxy;
        this.request = request;
        this.clientIp = clientIp;
    }

    // ------------------------------------------------------------------ variables

    public Object variable(String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith("request.header.")) {
            return request.headers().getFirst(name.substring("request.header.".length()));
        }
        if (name.startsWith("request.queryparam.")) {
            return request.queryParams().getFirst(name.substring("request.queryparam.".length()));
        }
        if (name.startsWith("response.header.")) {
            return response == null ? null : response.headers().getFirst(name.substring("response.header.".length()));
        }
        return switch (name) {
            case "organization.name" -> proxy.organization();
            case "environment.name" -> proxy.environment();
            case "apiproxy.name" -> proxy.proxy();
            case "apiproxy.revision" -> proxy.revision();
            case "proxy.basepath" -> proxy.basePath();
            case "proxy.pathsuffix" -> request.pathSuffix();
            case "request.verb" -> request.method().name();
            case "request.uri" -> request.uri();
            case "request.path" -> request.path();
            case "request.querystring" -> request.rawQuery();
            case "request.content" -> text(request.body());
            case "client.ip" -> clientIp;
            case "messageid" -> requestId;
            case "system.timestamp" -> System.currentTimeMillis();
            case "response.status.code" -> response == null ? null : response.status();
            case "response.content" -> response == null ? null : text(response.body());
            case "fault.name" -> fault == null ? null : fault.faultName();
            case "fault.message" -> fault == null ? null : fault.getMessage();
            case "error.status.code" -> fault == null ? null : fault.status();
            default -> variables.get(name);
        };
    }

    /** Read-only view of the variables set by policies (not the built-in ones). */
    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(variables);
    }

    /** The trace recorder when this request is being traced (a debug session matched it), else null. */
    public TraceRecorder trace() {
        return trace;
    }

    public void trace(TraceRecorder recorder) {
        this.trace = recorder;
    }

    public VariableResolver resolver() {
        return this::variable;
    }

    public String render(MessageTemplate template) {
        return template.render(resolver());
    }

    public void setVariable(String name, Object value) {
        if (value == null) {
            variables.remove(name);
        } else {
            variables.put(name, value);
        }
    }

    // ------------------------------------------------------------------ messages and phases

    public String requestId() {
        return requestId;
    }

    public ProxyInfo proxy() {
        return proxy;
    }

    public ProxyRequest request() {
        return request;
    }

    public ProxyResponse response() {
        return response;
    }

    public PolicyPhase phase() {
        return phase;
    }

    /** Ends the request flow with this response; the target is not called. */
    public void respond(ProxyResponse shortCircuitResponse) {
        this.response = shortCircuitResponse;
        this.shortCircuited = true;
    }

    public boolean isShortCircuited() {
        return shortCircuited;
    }

    public void beginResponse(ProxyResponse targetResponse) {
        this.response = targetResponse;
        this.phase = PolicyPhase.RESPONSE;
    }

    /** Switches to the error path with the standard error response for {@code f}. */
    public void enterFault(PolicyFault f) {
        this.fault = f;
        this.phase = PolicyPhase.FAULT;
        HttpHeaders headers = Headers.copy(f.headers());
        byte[] body = f.body();
        if (body == null) {
            body = f.defaultBody(requestId);
            headers.setContentType(MediaType.APPLICATION_JSON);
        } else if (f.contentType() != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, f.contentType());
        }
        this.response = new ProxyResponse(f.status(), headers, body);
    }

    public PolicyFault fault() {
        return fault;
    }

    // ------------------------------------------------------------------ cross-phase hooks

    /** Headers added to whatever response the client finally gets (success, short-circuit or fault). */
    public HttpHeaders pendingResponseHeaders() {
        return pendingResponseHeaders;
    }

    /** Runs after the response flow, before the response is written (e.g. ResponseCache populate). */
    public void onResponse(Function<MessageContext, Mono<Void>> callback) {
        responseCallbacks.add(callback);
    }

    public List<Function<MessageContext, Mono<Void>>> responseCallbacks() {
        return responseCallbacks;
    }

    /** Per-request private state for policies, keyed by e.g. policy name. */
    public Map<String, Object> attributes() {
        return attributes;
    }

    /** Variable prefix for a policy's outputs, e.g. {@code verifyapikey.verify-key.}. */
    public static String prefix(String policyType, String policyName) {
        return policyType.toLowerCase(Locale.ROOT) + "." + policyName + ".";
    }

    private static String text(byte[] body) {
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}

package com.portica.gateway.policy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Builds message contexts for policy unit tests (proxy "pets" deployed at /acme/dev/pets). */
public final class TestMessages {

    public static final MessageContext.ProxyInfo PROXY = new MessageContext.ProxyInfo("acme", "dev", "pets", 1, "/pets");
    private static final String PREFIX = "/acme/dev/pets";

    private final HttpMethod method;
    private final String pathAndQuery;
    private final HttpHeaders headers = new HttpHeaders();
    private byte[] body;
    private String clientIp = "203.0.113.7";

    private TestMessages(HttpMethod method, String pathAndQuery) {
        this.method = method;
        this.pathAndQuery = pathAndQuery;
    }

    /** {@code suffix} is the part after the base path, e.g. {@code /1?x=y}. */
    public static TestMessages request(String method, String suffix) {
        return new TestMessages(HttpMethod.valueOf(method), PREFIX + suffix);
    }

    public TestMessages header(String name, String value) {
        headers.add(name, value);
        return this;
    }

    public TestMessages body(String text) {
        this.body = text.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public TestMessages clientIp(String ip) {
        this.clientIp = ip;
        return this;
    }

    public MessageContext build() {
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String query = q < 0 ? null : pathAndQuery.substring(q + 1);
        ProxyRequest request = new ProxyRequest(method, path, query, path.substring(PREFIX.length()), headers);
        if (body != null) {
            request.initBody(body);
        }
        return new MessageContext("req-test-0001", PROXY, request, clientIp);
    }

    /** Adds a target response to a context (puts it in the RESPONSE phase). */
    public static MessageContext withResponse(MessageContext ctx, int status, String contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        ctx.beginResponse(new ProxyResponse(status, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
        return ctx;
    }

    /** Runs a policy; returns its fault, or null when it passed. */
    public static PolicyFault run(Policy policy, MessageContext ctx) {
        try {
            policy.execute(ctx).block(Duration.ofSeconds(10));
            return null;
        } catch (PolicyFault fault) {
            return fault;
        }
    }

    public static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}

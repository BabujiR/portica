package com.portica.gateway.policy;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The client request as policies see and change it. Changes are applied to the forwarded request
 * after the request flow. The path suffix is what follows the proxy's base path; it is appended to
 * the target path.
 */
public final class ProxyRequest {

    private HttpMethod method;
    private final String path;
    private final String rawQuery;
    private String pathSuffix;
    private MultiValueMap<String, String> queryParams;
    private boolean queryChanged;
    private boolean pathChanged;
    private final HttpHeaders headers;
    private byte[] body;
    private boolean bodyChanged;

    public ProxyRequest(HttpMethod method, String path, String rawQuery, String pathSuffix, HttpHeaders headers) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.pathSuffix = pathSuffix;
        this.headers = headers;
    }

    public HttpMethod method() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    /** Raw path as received by the gateway, e.g. {@code /acme/dev/pets/1}. */
    public String path() {
        return path;
    }

    public String rawQuery() {
        return rawQuery;
    }

    /** Path plus query string as received. */
    public String uri() {
        return rawQuery == null || rawQuery.isEmpty() ? path : path + "?" + rawQuery;
    }

    public String pathSuffix() {
        return pathSuffix;
    }

    public void setPathSuffix(String pathSuffix) {
        this.pathSuffix = pathSuffix;
        this.pathChanged = true;
    }

    public boolean pathChanged() {
        return pathChanged;
    }

    /** Decoded query parameters (mutable). */
    public MultiValueMap<String, String> queryParams() {
        if (queryParams == null) {
            queryParams = parseQuery(rawQuery);
        }
        return queryParams;
    }

    public void setQueryParam(String name, String value) {
        queryParams().put(name, new java.util.ArrayList<>(List.of(value)));
        queryChanged = true;
    }

    public void removeQueryParam(String name) {
        queryParams().remove(name);
        queryChanged = true;
    }

    public boolean queryChanged() {
        return queryChanged;
    }

    /** Encoded query string to forward. Untouched queries are forwarded byte-for-byte. */
    public String forwardedRawQuery() {
        if (!queryChanged) {
            return rawQuery;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : queryParams().entrySet()) {
            for (String v : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(UriUtils.encodeQueryParam(e.getKey(), StandardCharsets.UTF_8));
                if (v != null) {
                    sb.append('=').append(UriUtils.encodeQueryParam(v, StandardCharsets.UTF_8));
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public HttpHeaders headers() {
        return headers;
    }

    /** Body bytes, or null when the body was not buffered (no policy needed it). */
    public byte[] body() {
        return body;
    }

    public void initBody(byte[] body) {
        this.body = body;
    }

    public void setBody(byte[] body) {
        this.body = body;
        this.bodyChanged = true;
    }

    public boolean bodyLoaded() {
        return body != null;
    }

    public boolean bodyChanged() {
        return bodyChanged;
    }

    static MultiValueMap<String, String> parseQuery(String rawQuery) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? null : pair.substring(eq + 1);
            params.add(decode(name), value == null ? null : decode(value));
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return UriUtils.decode(s.replace("+", "%20"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}

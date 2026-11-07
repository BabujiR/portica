package com.portica.gateway.policy;

import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

/**
 * A policy rejecting the message, e.g. {@code InvalidApiKey} (401) or {@code QuotaViolation} (429).
 * The engine turns it into an error response, after letting fault rules customize it.
 * Has no stack trace: faults are normal control flow, not bugs.
 */
public class PolicyFault extends RuntimeException {

    private final String faultName;
    private final int status;
    private final HttpHeaders headers = new HttpHeaders();
    private byte[] body;
    private String contentType;

    public PolicyFault(String faultName, int status, String message) {
        super(message, null, false, false);
        this.faultName = faultName;
        this.status = status;
    }

    public static PolicyFault of(String faultName, int status, String message) {
        return new PolicyFault(faultName, status, message);
    }

    public PolicyFault header(String name, String value) {
        headers.set(name, value);
        return this;
    }

    /** A fully custom response body (RaiseFault). Without it the engine renders the standard JSON fault. */
    public PolicyFault body(String body, String contentType) {
        this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        this.contentType = contentType;
        return this;
    }

    public String faultName() {
        return faultName;
    }

    public int status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    /** Standard error document: {"fault":{"name":..,"message":..,"requestId":..}}. */
    public byte[] defaultBody(String requestId) {
        String json = "{\"fault\":{\"name\":\"" + escape(faultName) + "\",\"message\":\"" + escape(getMessage())
                + "\",\"requestId\":\"" + escape(requestId) + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

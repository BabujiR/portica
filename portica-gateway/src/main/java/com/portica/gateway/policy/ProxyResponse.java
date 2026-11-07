package com.portica.gateway.policy;

import org.springframework.http.HttpHeaders;

/** The response as policies see and change it (from the target, a short-circuit, or a fault). */
public final class ProxyResponse {

    private int status;
    private final HttpHeaders headers;
    private byte[] body;
    private boolean bodyChanged;

    public ProxyResponse(int status, HttpHeaders headers, byte[] body) {
        this.status = status;
        this.headers = headers == null ? new HttpHeaders() : headers;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    /** Body bytes, or null in header-only mode (no response policy needed the body). */
    public byte[] body() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
        this.bodyChanged = true;
    }

    public boolean bodyChanged() {
        return bodyChanged;
    }
}

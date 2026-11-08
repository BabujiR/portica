package com.portica.common.mcp;

/**
 * A failed MCP exchange: HTTP status (0 when unreachable), JSON-RPC error code when the server answered
 * with one, and the {@code WWW-Authenticate} header of a 401 (which points to OAuth metadata).
 */
public class McpClientException extends RuntimeException {

    private final int httpStatus;
    private final Integer rpcCode;
    private final String wwwAuthenticate;

    public McpClientException(int httpStatus, Integer rpcCode, String message, String wwwAuthenticate) {
        super(message);
        this.httpStatus = httpStatus;
        this.rpcCode = rpcCode;
        this.wwwAuthenticate = wwwAuthenticate;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Integer rpcCode() {
        return rpcCode;
    }

    public String wwwAuthenticate() {
        return wwwAuthenticate;
    }
}

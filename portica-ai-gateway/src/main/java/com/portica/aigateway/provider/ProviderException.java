package com.portica.aigateway.provider;

/**
 * A provider refused or failed a call. Retryable failures (rate limits, server errors, timeouts,
 * connection problems) move the call to the route's next target.
 */
public class ProviderException extends RuntimeException {

    private final int status;
    private final boolean retryable;
    private final String type;

    public ProviderException(int status, String type, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.type = type;
        this.retryable = retryable;
    }

    public static ProviderException of(int status, String message) {
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        String type = status == 429 ? "rate_limit_exceeded"
                : status == 401 || status == 403 ? "provider_authentication_error"
                : status >= 500 ? "provider_error" : "invalid_request_error";
        return new ProviderException(status, type, message, retryable);
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String type() {
        return type;
    }
}

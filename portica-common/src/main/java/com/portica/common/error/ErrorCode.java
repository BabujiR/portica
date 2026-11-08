package com.portica.common.error;

import java.util.Locale;

/**
 * Stable, machine-readable error codes shared by every Portica service.
 * <p>
 * Each code is exposed in RFC 7807 problem responses as the {@code code} property and is
 * also used to build the problem {@code type} URI, so clients can branch on it safely.
 */
public enum ErrorCode {

    BAD_REQUEST(400, "Bad request"),
    VALIDATION_FAILED(400, "Validation failed"),
    AUTHENTICATION_REQUIRED(401, "Authentication required"),
    ACCESS_DENIED(403, "Access denied"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    RESOURCE_CONFLICT(409, "Resource conflict"),
    CONCURRENT_MODIFICATION(409, "Concurrent modification"),
    BUSINESS_RULE_VIOLATION(422, "Business rule violation"),
    INTERNAL_ERROR(500, "Internal server error"),
    SERVICE_UNAVAILABLE(503, "Service unavailable");

    private static final String TYPE_BASE = "https://docs.portica.dev/problems/";

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    /** Problem type URI, e.g. {@code https://docs.portica.dev/problems/resource-not-found}. */
    public String typeUri() {
        return TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

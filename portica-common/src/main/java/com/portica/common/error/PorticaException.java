package com.portica.common.error;

import java.util.Objects;

/**
 * Base class for all expected (business) failures in Portica.
 * Services throw subclasses; the web layer maps them to RFC 7807 problem responses.
 */
public abstract class PorticaException extends RuntimeException {

    private final ErrorCode errorCode;

    protected PorticaException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

package com.portica.common.error;

/** A dependency (for example the analytics store) can't be reached right now; retrying later may work. */
public class ServiceUnavailableException extends PorticaException {

    public ServiceUnavailableException(String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message);
    }
}

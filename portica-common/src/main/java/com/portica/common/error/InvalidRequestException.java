package com.portica.common.error;

public class InvalidRequestException extends PorticaException {

    public InvalidRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }
}

package com.portica.common.error;

public class ResourceNotFoundException extends PorticaException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s '%s' was not found".formatted(resourceType, identifier));
    }
}

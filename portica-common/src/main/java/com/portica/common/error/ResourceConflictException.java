package com.portica.common.error;

public class ResourceConflictException extends PorticaException {

    public ResourceConflictException(String message) {
        super(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public static ResourceConflictException alreadyExists(String resourceType, String identifier) {
        return new ResourceConflictException("%s '%s' already exists".formatted(resourceType, identifier));
    }
}

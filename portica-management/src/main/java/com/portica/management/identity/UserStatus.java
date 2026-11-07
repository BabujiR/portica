package com.portica.management.identity;

public enum UserStatus {
    /** Added to an organization by email but has not signed in yet. */
    INVITED,
    /** Linked to a Keycloak identity. */
    ACTIVE,
    /** Blocked from the management API regardless of role bindings. */
    DISABLED
}

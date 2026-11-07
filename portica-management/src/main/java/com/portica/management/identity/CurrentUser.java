package com.portica.management.identity;

import java.util.UUID;

/**
 * The authenticated caller, resolved once per request.
 *
 * @param id             Portica user id
 * @param subject        Keycloak subject ({@code sub} claim)
 * @param email          normalized email, may be null
 * @param displayName    display name
 * @param platformAdmin  whether the token carries the platform admin realm role
 */
public record CurrentUser(UUID id, String subject, String email, String displayName, boolean platformAdmin) {

    /** Value recorded as the actor in audit events. */
    public String actor() {
        return email != null ? email : subject;
    }
}

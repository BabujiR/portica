package com.portica.management.rbac;

import com.portica.management.identity.CurrentUser;
import com.portica.management.identity.CurrentUserService;
import com.portica.management.organization.OrganizationStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Organization-scoped authorization, exposed to SpEL as {@code @authz}:
 * <pre>{@code @PreAuthorize("@authz.hasPermission(#org, 'ENV_CREATE')")}</pre>
 * Platform administrators (Keycloak realm role) are allowed everything. Everyone else needs a
 * role binding in an <em>active</em> organization whose role grants the permission.
 * Callers without access get 403; a non-existent organization also yields 403 for non-admins,
 * so organization names cannot be probed.
 */
@Component("authz")
public class AuthorizationService {

    private final CurrentUserService currentUserService;
    private final RoleBindingRepository roleBindings;

    public AuthorizationService(CurrentUserService currentUserService, RoleBindingRepository roleBindings) {
        this.currentUserService = currentUserService;
        this.roleBindings = roleBindings;
    }

    public boolean isPlatformAdmin() {
        return currentUserService.isPlatformAdmin();
    }

    /** Gateways (service accounts with the runtime role) and platform admins may read runtime configuration. */
    /** The developer portal's service account (it passes the signed-in developer's e-mail address). */
    public boolean isPortalClient() {
        return currentUserService.isPortalClient();
    }

    public boolean canReadRuntimeConfig() {
        return currentUserService.isRuntimeClient() || currentUserService.isPlatformAdmin();
    }

    public boolean hasPermission(String organizationName, String permission) {
        Permission required = Permission.valueOf(permission); // fails fast on typos in annotations
        if (isPlatformAdmin()) {
            return true;
        }
        if (organizationName == null || organizationName.isBlank()) {
            return false;
        }
        CurrentUser user = currentUserService.currentUser();
        return permissionsOf(user.id(), organizationName).contains(required);
    }

    public Set<Permission> permissionsOf(UUID userId, String organizationName) {
        List<Permission> granted = roleBindings.findPermissions(userId, organizationName, OrganizationStatus.ACTIVE);
        return granted.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(granted);
    }
}

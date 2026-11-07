package com.portica.management.rbac;

import com.portica.management.identity.CurrentUser;
import com.portica.management.identity.CurrentUserService;
import com.portica.management.organization.OrganizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final RoleBindingRepository bindings = mock(RoleBindingRepository.class);
    private final AuthorizationService authz = new AuthorizationService(currentUserService, bindings);

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUserService.currentUser())
                .thenReturn(new CurrentUser(userId, "sub", "alice@example.com", "Alice", false));
    }

    @Test
    void platformAdminIsAllowedEverythingWithoutDatabaseLookup() {
        when(currentUserService.isPlatformAdmin()).thenReturn(true);

        assertThat(authz.hasPermission("any-org", "ENV_DELETE")).isTrue();
        verify(bindings, never()).findPermissions(any(), any(), any());
    }

    @Test
    void memberNeedsTheSpecificPermissionInThatOrganization() {
        when(bindings.findPermissions(userId, "acme", OrganizationStatus.ACTIVE))
                .thenReturn(List.of(Permission.ORG_READ, Permission.ENV_READ));

        assertThat(authz.hasPermission("acme", "ENV_READ")).isTrue();
        assertThat(authz.hasPermission("acme", "ENV_CREATE")).isFalse();
    }

    @Test
    void nonMemberIsDenied() {
        when(bindings.findPermissions(userId, "other", OrganizationStatus.ACTIVE)).thenReturn(List.of());

        assertThat(authz.hasPermission("other", "ORG_READ")).isFalse();
    }

    @Test
    void blankOrganizationIsDenied() {
        assertThat(authz.hasPermission(" ", "ORG_READ")).isFalse();
    }

    @Test
    void unknownPermissionNameFailsFast() {
        assertThatThrownBy(() -> authz.hasPermission("acme", "ENV_TYPO"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

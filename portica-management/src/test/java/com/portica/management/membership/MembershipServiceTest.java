package com.portica.management.membership;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.identity.User;
import com.portica.management.identity.UserRepository;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.rbac.Role;
import com.portica.management.rbac.RoleBinding;
import com.portica.management.rbac.RoleBindingRepository;
import com.portica.management.rbac.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipServiceTest {

    private final OrganizationFinder organizations = mock(OrganizationFinder.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleBindingRepository bindings = mock(RoleBindingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final MembershipService service = new MembershipService(organizations, users, roles, bindings, audit);

    private final Organization org = new Organization("acme", "Acme", null);
    private final UUID userId = UUID.randomUUID();
    private final User alice = User.active("alice-sub", "alice@example.com", "Alice");
    private final Role orgAdmin = role(Role.ORG_ADMIN);
    private final Role viewer = role("VIEWER");

    @BeforeEach
    void setUp() {
        when(organizations.getActive("acme")).thenReturn(org);
        when(bindings.existsByOrganization_IdAndUser_Id(any(), eq(userId))).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(alice));
        when(bindings.findForMember(any(), eq(userId))).thenReturn(List.of(new RoleBinding(org, alice, orgAdmin)));
        when(roles.findByCodeIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> codes = inv.getArgument(0);
            return Stream.of(orgAdmin, viewer).filter(r -> codes.contains(r.getCode())).toList();
        });
    }

    @Test
    void cannotDemoteTheLastOrgAdmin() {
        when(bindings.countByOrganization_IdAndRole_Code(any(), eq(Role.ORG_ADMIN))).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceRoles("acme", userId, Set.of("VIEWER")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least one ORG_ADMIN");
        verify(bindings, never()).deleteAll(any());
    }

    @Test
    void cannotRemoveTheLastOrgAdmin() {
        when(bindings.countByOrganization_IdAndRole_Code(any(), eq(Role.ORG_ADMIN))).thenReturn(1L);

        assertThatThrownBy(() -> service.remove("acme", userId))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(organizations).lock(org); // the check runs under an organization row lock
        verify(bindings, never()).deleteAll(any());
    }

    @Test
    void canRemoveAnAdminWhenAnotherRemains() {
        when(bindings.countByOrganization_IdAndRole_Code(any(), eq(Role.ORG_ADMIN))).thenReturn(2L);

        service.remove("acme", userId);

        verify(bindings).deleteAll(any());
        verify(audit).record(any(), eq("member.removed"), anyString(), anyString(), any());
    }

    @Test
    void unknownRolesAreRejected() {
        assertThatThrownBy(() -> service.replaceRoles("acme", userId, Set.of("VIEWER", "superuser")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("SUPERUSER");
    }

    @Test
    void nonMemberIsNotFound() {
        UUID stranger = UUID.randomUUID();
        when(bindings.existsByOrganization_IdAndUser_Id(any(), eq(stranger))).thenReturn(false);

        assertThatThrownBy(() -> service.remove("acme", stranger)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static Role role(String code) {
        Role role = mock(Role.class);
        when(role.getCode()).thenReturn(code);
        return role;
    }
}

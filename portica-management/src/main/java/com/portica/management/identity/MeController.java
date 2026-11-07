package com.portica.management.identity;

import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationStatus;
import com.portica.management.rbac.Permission;
import com.portica.management.rbac.RoleBinding;
import com.portica.management.rbac.RoleBindingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The signed-in user and what they can do")
public class MeController {

    private final CurrentUserService currentUserService;
    private final RoleBindingRepository roleBindings;

    public MeController(CurrentUserService currentUserService, RoleBindingRepository roleBindings) {
        this.currentUserService = currentUserService;
        this.roleBindings = roleBindings;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Current user, memberships and effective permissions per organization",
            description = "Signing in for the first time provisions the user and accepts pending invitations.")
    public MeResponse me() {
        CurrentUser user = currentUserService.currentUser();

        Map<String, MembershipView> byOrg = new LinkedHashMap<>();
        for (RoleBinding rb : roleBindings.findForUser(user.id(), OrganizationStatus.ACTIVE)) {
            Organization org = rb.getOrganization();
            MembershipView view = byOrg.computeIfAbsent(org.getName(),
                    n -> new MembershipView(org.getName(), org.getDisplayName(), new TreeSet<>(),
                            EnumSet.noneOf(Permission.class)));
            view.roles().add(rb.getRole().getCode());
            view.permissions().addAll(rb.getRole().getPermissions());
        }
        List<MembershipResponse> memberships = byOrg.values().stream()
                .map(v -> new MembershipResponse(v.organization(), v.organizationDisplayName(),
                        List.copyOf(v.roles()), v.permissions().stream().map(Enum::name).sorted().toList()))
                .toList();
        return new MeResponse(user.id(), user.subject(), user.email(), user.displayName(), user.platformAdmin(),
                memberships);
    }

    private record MembershipView(String organization, String organizationDisplayName,
                                  Set<String> roles, Set<Permission> permissions) {
    }

    public record MembershipResponse(String organization, String organizationDisplayName,
                                     List<String> roles, List<String> permissions) {
    }

    public record MeResponse(UUID id, String subject, String email, String displayName, boolean platformAdmin,
                             List<MembershipResponse> memberships) {
    }
}

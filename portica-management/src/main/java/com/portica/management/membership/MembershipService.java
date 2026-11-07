package com.portica.management.membership;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.identity.User;
import com.portica.management.identity.UserRepository;
import com.portica.management.membership.MembershipDtos.MemberResponse;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.rbac.Role;
import com.portica.management.rbac.RoleBinding;
import com.portica.management.rbac.RoleBindingRepository;
import com.portica.management.rbac.RoleRepository;
import com.portica.management.shared.PageResponses;
import com.portica.management.shared.SearchTerms;
import com.portica.common.api.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Organization membership = role bindings. Enforces that every organization keeps at least
 * one {@code ORG_ADMIN}, so an organization can never be orphaned by its own members.
 */
@Service
public class MembershipService {

    static final String RESOURCE_TYPE = "Member";

    private final OrganizationFinder organizations;
    private final UserRepository users;
    private final RoleRepository roles;
    private final RoleBindingRepository bindings;
    private final AuditService audit;

    public MembershipService(OrganizationFinder organizations, UserRepository users, RoleRepository roles,
                             RoleBindingRepository bindings, AuditService audit) {
        this.organizations = organizations;
        this.users = users;
        this.roles = roles;
        this.bindings = bindings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(String orgName, String q, Pageable pageable) {
        Organization org = organizations.getActive(orgName);
        Page<User> page = users.searchMembers(org.getId(), SearchTerms.normalize(q), pageable);
        List<UUID> userIds = page.getContent().stream().map(User::getId).toList();
        Map<UUID, List<RoleBinding>> byUser = userIds.isEmpty()
                ? Map.of()
                : bindings.findForMembers(org.getId(), userIds).stream()
                .collect(Collectors.groupingBy(rb -> rb.getUser().getId()));
        List<MemberResponse> items = page.getContent().stream()
                .map(u -> toResponse(u, byUser.getOrDefault(u.getId(), List.of())))
                .toList();
        return PageResponses.of(page, items);
    }

    @Transactional(readOnly = true)
    public MemberResponse get(String orgName, UUID userId) {
        Organization org = organizations.getActive(orgName);
        User user = requireMember(org, userId);
        return toResponse(user, bindings.findForMember(org.getId(), userId));
    }

    @Transactional
    public MemberResponse add(String orgName, String email, Set<String> roleCodes) {
        Organization org = organizations.getActive(orgName);
        List<Role> requested = resolveRoles(roleCodes);
        String normalizedEmail = User.normalizeEmail(email);
        User user = users.findByEmail(normalizedEmail).orElseGet(() -> users.save(User.invited(normalizedEmail)));
        if (bindings.existsByOrganization_IdAndUser_Id(org.getId(), user.getId())) {
            throw new ResourceConflictException(
                    "'%s' is already a member of '%s'; update their roles instead".formatted(normalizedEmail, orgName));
        }
        List<RoleBinding> created = grant(org, user, requested);
        audit.record(org.getId(), "member.added", RESOURCE_TYPE, normalizedEmail,
                Map.of("userId", user.getId().toString(), "roles", codes(requested)));
        return toResponse(user, created);
    }

    /** Grants roles without membership checks or auditing; used when bootstrapping an organization. */
    @Transactional
    public void grantInitialAdmin(Organization org, User user) {
        grant(org, user, resolveRoles(Set.of(Role.ORG_ADMIN)));
    }

    /** Grants the initial admin by email, inviting the user if needed. Returns the user. */
    @Transactional
    public User grantInitialAdmin(Organization org, String email) {
        String normalizedEmail = User.normalizeEmail(email);
        User user = users.findByEmail(normalizedEmail).orElseGet(() -> users.save(User.invited(normalizedEmail)));
        grantInitialAdmin(org, user);
        return user;
    }

    @Transactional
    public MemberResponse replaceRoles(String orgName, UUID userId, Set<String> roleCodes) {
        Organization org = organizations.getActive(orgName);
        User user = requireMember(org, userId);
        List<Role> requested = resolveRoles(roleCodes);
        Set<String> requestedCodes = codes(requested);

        List<RoleBinding> current = bindings.findForMember(org.getId(), userId);
        Set<String> currentCodes = current.stream().map(rb -> rb.getRole().getCode())
                .collect(Collectors.toCollection(TreeSet::new));
        if (currentCodes.contains(Role.ORG_ADMIN) && !requestedCodes.contains(Role.ORG_ADMIN)) {
            ensureAnotherAdminRemains(org);
        }

        // Diff instead of delete-all + insert: Hibernate flushes inserts before deletes,
        // which would violate the unique (organization, user, role) constraint.
        List<RoleBinding> toRemove = current.stream()
                .filter(rb -> !requestedCodes.contains(rb.getRole().getCode())).toList();
        List<Role> toAdd = requested.stream().filter(r -> !currentCodes.contains(r.getCode())).toList();
        bindings.deleteAll(toRemove);
        grant(org, user, toAdd);

        audit.record(org.getId(), "member.roles.updated", RESOURCE_TYPE, label(user),
                Map.of("userId", userId.toString(), "from", currentCodes, "to", requestedCodes));
        return new MemberResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getStatus().name(),
                List.copyOf(requestedCodes), user.getLastLoginAt());
    }

    @Transactional
    public void remove(String orgName, UUID userId) {
        Organization org = organizations.getActive(orgName);
        User user = requireMember(org, userId);
        List<RoleBinding> current = bindings.findForMember(org.getId(), userId);
        boolean isAdmin = current.stream().anyMatch(rb -> Role.ORG_ADMIN.equals(rb.getRole().getCode()));
        if (isAdmin) {
            ensureAnotherAdminRemains(org);
        }
        bindings.deleteAll(current);
        audit.record(org.getId(), "member.removed", RESOURCE_TYPE, label(user),
                Map.of("userId", userId.toString()));
    }

    private void ensureAnotherAdminRemains(Organization org) {
        // Serialize admin removals per organization so two concurrent requests cannot both pass the check.
        organizations.lock(org);
        if (bindings.countByOrganization_IdAndRole_Code(org.getId(), Role.ORG_ADMIN) <= 1) {
            throw new BusinessRuleViolationException(
                    "Organization '%s' must keep at least one %s".formatted(org.getName(), Role.ORG_ADMIN));
        }
    }

    private User requireMember(Organization org, UUID userId) {
        if (!bindings.existsByOrganization_IdAndUser_Id(org.getId(), userId)) {
            throw new ResourceNotFoundException(RESOURCE_TYPE, userId.toString());
        }
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, userId.toString()));
    }

    private List<Role> resolveRoles(Collection<String> roleCodes) {
        Set<String> normalized = roleCodes.stream()
                .map(c -> c.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Role> found = roles.findByCodeIn(normalized).stream()
                .collect(Collectors.toMap(Role::getCode, Function.identity()));
        List<String> unknown = normalized.stream().filter(c -> !found.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Unknown role(s): " + String.join(", ", unknown));
        }
        return normalized.stream().map(found::get).toList();
    }

    private List<RoleBinding> grant(Organization org, User user, List<Role> toGrant) {
        return bindings.saveAll(toGrant.stream().map(role -> new RoleBinding(org, user, role)).toList());
    }

    private static Set<String> codes(List<Role> roles) {
        return roles.stream().map(Role::getCode).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String label(User user) {
        return user.getEmail() != null ? user.getEmail() : user.getId().toString();
    }

    private static MemberResponse toResponse(User user, List<RoleBinding> userBindings) {
        List<String> roleCodes = userBindings.stream().map(rb -> rb.getRole().getCode()).sorted().toList();
        return new MemberResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getStatus().name(),
                roleCodes, user.getLastLoginAt());
    }
}

package com.portica.management.organization;

import com.portica.common.error.ResourceConflictException;
import com.portica.management.audit.AuditService;
import com.portica.management.config.PorticaOrganizationProperties;
import com.portica.management.environment.EnvironmentService;
import com.portica.management.identity.CurrentUser;
import com.portica.management.identity.CurrentUserService;
import com.portica.management.identity.User;
import com.portica.management.identity.UserRepository;
import com.portica.management.membership.MembershipService;
import com.portica.management.organization.OrganizationDtos.CreateOrganizationRequest;
import com.portica.management.organization.OrganizationDtos.UpdateOrganizationRequest;
import com.portica.management.shared.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationFinder finder;
    private final EnvironmentService environments;
    private final MembershipService memberships;
    private final UserRepository users;
    private final CurrentUserService currentUserService;
    private final AuditService audit;
    private final PorticaOrganizationProperties properties;

    public OrganizationService(OrganizationRepository organizations, OrganizationFinder finder,
                               EnvironmentService environments, MembershipService memberships,
                               UserRepository users, CurrentUserService currentUserService,
                               AuditService audit, PorticaOrganizationProperties properties) {
        this.organizations = organizations;
        this.finder = finder;
        this.environments = environments;
        this.memberships = memberships;
        this.users = users;
        this.currentUserService = currentUserService;
        this.audit = audit;
        this.properties = properties;
    }

    /** Platform admins see every organization (including archived); others see active ones they belong to. */
    @Transactional(readOnly = true)
    public Page<Organization> list(String q, Pageable pageable) {
        String term = SearchTerms.normalize(q);
        if (currentUserService.isPlatformAdmin()) {
            return organizations.search(term, pageable);
        }
        CurrentUser user = currentUserService.currentUser();
        return organizations.searchForMember(user.id(), OrganizationStatus.ACTIVE, term, pageable);
    }

    @Transactional(readOnly = true)
    public Organization get(String name) {
        return currentUserService.isPlatformAdmin() ? finder.getAny(name) : finder.getActive(name);
    }

    /**
     * Creates an organization, its default environments, and its first ORG_ADMIN
     * (the {@code adminEmail} user, invited if needed, or else the caller) in one transaction.
     */
    @Transactional
    public Organization create(CreateOrganizationRequest request) {
        if (organizations.existsByName(request.name())) {
            throw ResourceConflictException.alreadyExists(OrganizationFinder.RESOURCE_TYPE, request.name());
        }
        Organization org = organizations.save(
                new Organization(request.name(), request.displayName(), request.description()));

        List<String> createdEnvironments = new ArrayList<>();
        if (!Boolean.FALSE.equals(request.createDefaultEnvironments())) {
            for (String env : properties.defaultEnvironments()) {
                environments.createInternal(org, env, null, null);
                createdEnvironments.add(env);
            }
        }

        String admin;
        if (request.adminEmail() != null && !request.adminEmail().isBlank()) {
            User adminUser = memberships.grantInitialAdmin(org, request.adminEmail());
            admin = adminUser.getEmail();
        } else {
            CurrentUser caller = currentUserService.currentUser();
            memberships.grantInitialAdmin(org, users.getReferenceById(caller.id()));
            admin = caller.actor();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("displayName", org.getDisplayName());
        details.put("environments", createdEnvironments);
        details.put("initialAdmin", admin);
        audit.record(org.getId(), "organization.created", OrganizationFinder.RESOURCE_TYPE, org.getName(), details);
        return org;
    }

    @Transactional
    public Organization update(String name, UpdateOrganizationRequest request) {
        Organization org = finder.getActive(name);
        org.update(request.displayName(), request.description());
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.displayName() != null) {
            changes.put("displayName", request.displayName());
        }
        if (request.description() != null) {
            changes.put("descriptionChanged", true);
        }
        audit.record(org.getId(), "organization.updated", OrganizationFinder.RESOURCE_TYPE, name, changes);
        return org;
    }

    /** Soft delete. Data is retained; the name stays reserved; members lose access immediately. */
    @Transactional
    public void archive(String name) {
        Organization org = finder.getActive(name);
        org.archive();
        audit.record(org.getId(), "organization.archived", OrganizationFinder.RESOURCE_TYPE, name, Map.of());
    }
}

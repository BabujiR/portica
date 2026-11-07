package com.portica.management.product;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.developer.AppCredentialProductRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentRepository;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.product.ApiProductDtos.CreateApiProductRequest;
import com.portica.management.product.ApiProductDtos.UpdateApiProductRequest;
import com.portica.management.runtime.RuntimeChanges;
import com.portica.management.shared.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** API products. Every change reaches gateways (it changes what credentials may call). */
@Service
public class ApiProductService {

    static final String RESOURCE_TYPE = "ApiProduct";

    private final ApiProductRepository products;
    private final AppCredentialProductRepository grants;
    private final OrganizationFinder organizations;
    private final EnvironmentRepository environments;
    private final RuntimeChanges runtimeChanges;
    private final AuditService audit;

    public ApiProductService(ApiProductRepository products, AppCredentialProductRepository grants,
                             OrganizationFinder organizations, EnvironmentRepository environments,
                             RuntimeChanges runtimeChanges, AuditService audit) {
        this.products = products;
        this.grants = grants;
        this.organizations = organizations;
        this.environments = environments;
        this.runtimeChanges = runtimeChanges;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<ApiProduct> list(String org, String q, Pageable pageable) {
        return products.search(organizations.getActive(org).getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public ApiProduct get(String org, String name) {
        return find(organizations.getActive(org), name);
    }

    @Transactional
    public ApiProduct create(String org, CreateApiProductRequest r) {
        Organization organization = organizations.getActive(org);
        if (products.existsByOrganization_IdAndName(organization.getId(), r.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, r.name());
        }
        requireEnvironments(organization, r.environments());
        ApiProduct product = new ApiProduct(organization, r.name());
        product.define(r.displayName(), r.description(), r.approvalType(), r.access(), r.environments(), r.proxies(),
                r.paths(), r.scopes(), r.quota(), r.attributes());
        product = products.save(product);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "api-product.created", RESOURCE_TYPE, product.getName(), summary(product));
        return product;
    }

    @Transactional
    public ApiProduct update(String org, String name, UpdateApiProductRequest r) {
        Organization organization = organizations.getActive(org);
        ApiProduct product = find(organization, name);
        requireEnvironments(organization, r.environments());
        product.define(r.displayName(), r.description(), r.approvalType(), r.access(), r.environments(), r.proxies(),
                r.paths(), r.scopes(), r.quota(), r.attributes());
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "api-product.updated", RESOURCE_TYPE, name, summary(product));
        return product;
    }

    /** Refused while any credential holds the product: remove it from those apps first. */
    @Transactional
    public void delete(String org, String name) {
        Organization organization = organizations.getActive(org);
        ApiProduct product = find(organization, name);
        long used = grants.countByApiProduct_Id(product.getId());
        if (used > 0) {
            throw new BusinessRuleViolationException("API product '%s' is used by %d app credential(s); remove it from those apps first"
                    .formatted(name, used));
        }
        products.delete(product);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "api-product.deleted", RESOURCE_TYPE, name, Map.of());
    }

    /** Products of the organization by name; throws listing every unknown name. Call inside a transaction. */
    public List<ApiProduct> requireAll(Organization organization, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> distinct = names.stream().distinct().toList();
        List<ApiProduct> found = products.findByOrganization_IdAndNameIn(organization.getId(), distinct);
        Set<String> foundNames = found.stream().map(ApiProduct::getName).collect(Collectors.toSet());
        List<String> missing = distinct.stream().filter(n -> !foundNames.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException("Unknown API product(s): " + String.join(", ", missing));
        }
        Map<String, ApiProduct> byName = found.stream().collect(Collectors.toMap(ApiProduct::getName, p -> p));
        return distinct.stream().map(byName::get).toList();
    }

    public ApiProduct find(Organization organization, String name) {
        return products.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private void requireEnvironments(Organization organization, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        Set<String> known = environments.findByOrganization_Id(organization.getId()).stream()
                .map(Environment::getName).collect(Collectors.toSet());
        List<String> missing = names.stream().filter(n -> !known.contains(n)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException("Unknown environment(s): " + String.join(", ", missing));
        }
    }

    private static Map<String, Object> summary(ApiProduct p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvalType", p.getApprovalType());
        details.put("environments", p.environments());
        details.put("proxies", p.proxies());
        details.put("scopes", p.scopes());
        details.put("quota", p.quota());
        return details;
    }
}

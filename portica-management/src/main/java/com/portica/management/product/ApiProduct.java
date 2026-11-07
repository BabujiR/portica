package com.portica.management.product;

import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import com.portica.management.shared.JsonColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An API product: the unit apps subscribe to. It grants access to API proxies (optionally only some
 * paths) in some environments, carries the OAuth scopes apps can request and an optional quota.
 */
@Entity
@Table(name = "api_products")
public class ApiProduct extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 16)
    private ApprovalType approvalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "access", nullable = false, length = 16)
    private ProductAccess access;

    @Column(name = "environments", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String environmentsJson;

    @Column(name = "proxies", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String proxiesJson;

    @Column(name = "paths", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String pathsJson;

    @Column(name = "scopes", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String scopesJson;

    @Column(name = "quota_count")
    private Long quotaCount;

    @Column(name = "quota_interval")
    private Integer quotaInterval;

    @Column(name = "quota_time_unit", length = 16)
    private String quotaTimeUnit;

    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String attributesJson;

    protected ApiProduct() {
        // JPA
    }

    public ApiProduct(Organization organization, String name) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = name;
        this.approvalType = ApprovalType.AUTO;
        this.access = ProductAccess.PUBLIC;
        this.environmentsJson = JsonColumns.writeList(List.of());
        this.proxiesJson = JsonColumns.writeList(List.of());
        this.pathsJson = JsonColumns.writeList(List.of());
        this.scopesJson = JsonColumns.writeList(List.of());
        this.attributesJson = JsonColumns.writeMap(Map.of());
    }

    /** Replaces every mutable field (the API uses full replacement for products). */
    public void define(String displayName, String description, ApprovalType approvalType, ProductAccess access,
                       List<String> environments, List<String> proxies, List<String> paths, List<String> scopes,
                       ProductQuota quota, Map<String, String> attributes) {
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description == null || description.isBlank() ? null : description;
        this.approvalType = approvalType == null ? ApprovalType.AUTO : approvalType;
        this.access = access == null ? ProductAccess.PUBLIC : access;
        this.environmentsJson = JsonColumns.writeList(distinct(environments));
        this.proxiesJson = JsonColumns.writeList(distinct(proxies));
        this.pathsJson = JsonColumns.writeList(distinct(paths));
        this.scopesJson = JsonColumns.writeList(distinct(scopes));
        this.quotaCount = quota == null ? null : quota.count();
        this.quotaInterval = quota == null ? null : quota.interval();
        this.quotaTimeUnit = quota == null ? null : quota.timeUnit();
        this.attributesJson = JsonColumns.writeMap(attributes);
    }

    private static List<String> distinct(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::strip).distinct().toList();
    }

    /** True when the product is offered in {@code environment} (no environments listed means all). */
    public boolean availableIn(String environment) {
        List<String> envs = environments();
        return envs.isEmpty() || envs.contains(environment);
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public ApprovalType getApprovalType() {
        return approvalType;
    }

    public ProductAccess getAccess() {
        return access;
    }

    public List<String> environments() {
        return JsonColumns.readList(environmentsJson);
    }

    public List<String> proxies() {
        return JsonColumns.readList(proxiesJson);
    }

    public List<String> paths() {
        return JsonColumns.readList(pathsJson);
    }

    public List<String> scopes() {
        return JsonColumns.readList(scopesJson);
    }

    public ProductQuota quota() {
        return quotaCount == null ? null : new ProductQuota(quotaCount, quotaInterval, quotaTimeUnit);
    }

    public Map<String, String> attributes() {
        return JsonColumns.readMap(attributesJson);
    }
}

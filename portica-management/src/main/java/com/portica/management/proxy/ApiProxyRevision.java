package com.portica.management.proxy;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.portica.common.policy.BundleJson;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import org.hibernate.annotations.ColumnTransformer;

import java.util.List;
import java.util.Objects;

/**
 * An immutable snapshot of an API proxy's runtime behaviour. Changing anything means creating a
 * new revision, so what is deployed is always exactly what was reviewed, and rollback is just
 * deploying an older revision. Policies and flows are added to revisions in Phase 3.
 */
@Entity
@Table(name = "api_proxy_revisions")
public class ApiProxyRevision extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_proxy_id", nullable = false, updatable = false)
    private ApiProxy apiProxy;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "description", updatable = false, length = 1024)
    private String description;

    @Column(name = "base_path", nullable = false, updatable = false)
    private String basePath;

    @Column(name = "target_url", updatable = false, length = 2048)
    private String targetUrl;

    @Column(name = "target_server", updatable = false, length = 63)
    private String targetServer;

    @Column(name = "target_path", nullable = false, updatable = false, length = 1024)
    private String targetPath;

    @Column(name = "connect_timeout_ms", nullable = false, updatable = false)
    private int connectTimeoutMs;

    @Column(name = "read_timeout_ms", nullable = false, updatable = false)
    private int readTimeoutMs;

    @Column(name = "openapi_spec", updatable = false, columnDefinition = "text")
    private String openApiSpec;

    /** JSON array of {@link PolicyDefinition}. */
    @Column(name = "policies", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String policiesJson;

    /** JSON {@link ProxyFlows}. */
    @Column(name = "flows", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String flowsJson;

    protected ApiProxyRevision() {
        // JPA
    }

    ApiProxyRevision(ApiProxy apiProxy, int revision, String description, String basePath, TargetSpec target,
                     int connectTimeoutMs, int readTimeoutMs, String openApiSpec, List<PolicyDefinition> policies,
                     ProxyFlows flows) {
        this.apiProxy = Objects.requireNonNull(apiProxy, "apiProxy");
        this.revision = revision;
        this.description = description;
        this.basePath = Objects.requireNonNull(basePath, "basePath");
        this.targetUrl = target.url();
        this.targetServer = target.server();
        this.targetPath = target.path();
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.openApiSpec = openApiSpec;
        this.policiesJson = BundleJson.writePolicies(policies);
        this.flowsJson = BundleJson.writeFlows(flows);
    }

    public List<PolicyDefinition> policies() {
        return BundleJson.readPolicies(policiesJson);
    }

    public ProxyFlows flows() {
        return BundleJson.readFlows(flowsJson);
    }

    public TargetSpec target() {
        return new TargetSpec(targetUrl, targetServer, targetPath);
    }

    public ApiProxy getApiProxy() {
        return apiProxy;
    }

    public int getRevision() {
        return revision;
    }

    public String getDescription() {
        return description;
    }

    public String getBasePath() {
        return basePath;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public String getOpenApiSpec() {
        return openApiSpec;
    }
}

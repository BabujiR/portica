package com.portica.management.deployment;

import com.portica.management.environment.Environment;
import com.portica.management.proxy.ApiProxy;
import com.portica.management.proxy.ApiProxyRevision;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * "Revision R of proxy P is live in environment E." At most one revision per proxy per
 * environment; deploying another revision swaps this row in place, which the gateway applies
 * as a single atomic route replacement (zero downtime).
 */
@Entity
@Table(name = "deployments")
public class Deployment extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_proxy_id", nullable = false, updatable = false)
    private ApiProxy apiProxy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private ApiProxyRevision revision;

    /** Copied from the revision so the database can enforce unique base paths per environment. */
    @Column(name = "base_path", nullable = false)
    private String basePath;

    protected Deployment() {
        // JPA
    }

    public Deployment(Environment environment, ApiProxyRevision revision) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.apiProxy = revision.getApiProxy();
        switchTo(revision);
    }

    public final void switchTo(ApiProxyRevision newRevision) {
        if (apiProxy != null && newRevision.getApiProxy() != apiProxy
                && !Objects.equals(newRevision.getApiProxy().getId(), apiProxy.getId())) {
            throw new IllegalArgumentException("Revision belongs to a different API proxy");
        }
        this.revision = newRevision;
        this.basePath = newRevision.getBasePath();
    }

    public Environment getEnvironment() {
        return environment;
    }

    public ApiProxy getApiProxy() {
        return apiProxy;
    }

    public ApiProxyRevision getRevision() {
        return revision;
    }

    public String getBasePath() {
        return basePath;
    }
}

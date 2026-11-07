package com.portica.management.sharedflow;

import com.portica.management.environment.Environment;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/** "Revision R of shared flow S is live in environment E." At most one revision per environment. */
@Entity
@Table(name = "shared_flow_deployments")
public class SharedFlowDeployment extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_flow_id", nullable = false, updatable = false)
    private SharedFlow sharedFlow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private SharedFlowRevision revision;

    protected SharedFlowDeployment() {
        // JPA
    }

    public SharedFlowDeployment(Environment environment, SharedFlowRevision revision) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.sharedFlow = revision.getSharedFlow();
        this.revision = revision;
    }

    public void switchTo(SharedFlowRevision newRevision) {
        this.revision = newRevision;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public SharedFlow getSharedFlow() {
        return sharedFlow;
    }

    public SharedFlowRevision getRevision() {
        return revision;
    }
}

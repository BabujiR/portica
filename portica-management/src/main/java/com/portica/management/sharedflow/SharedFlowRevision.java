package com.portica.management.sharedflow;

import com.portica.common.policy.BundleJson;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.util.List;
import java.util.Objects;

/** Immutable policies and steps of one shared flow revision. */
@Entity
@Table(name = "shared_flow_revisions")
public class SharedFlowRevision extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_flow_id", nullable = false, updatable = false)
    private SharedFlow sharedFlow;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "description", updatable = false, length = 1024)
    private String description;

    @Column(name = "policies", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String policiesJson;

    @Column(name = "steps", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String stepsJson;

    protected SharedFlowRevision() {
        // JPA
    }

    SharedFlowRevision(SharedFlow sharedFlow, int revision, String description, List<PolicyDefinition> policies,
                       List<FlowStep> steps) {
        this.sharedFlow = Objects.requireNonNull(sharedFlow, "sharedFlow");
        this.revision = revision;
        this.description = description;
        this.policiesJson = BundleJson.writePolicies(policies);
        this.stepsJson = BundleJson.writeSteps(steps);
    }

    public List<PolicyDefinition> policies() {
        return BundleJson.readPolicies(policiesJson);
    }

    public List<FlowStep> steps() {
        return BundleJson.readSteps(stepsJson);
    }

    public SharedFlow getSharedFlow() {
        return sharedFlow;
    }

    public int getRevision() {
        return revision;
    }

    public String getDescription() {
        return description;
    }
}

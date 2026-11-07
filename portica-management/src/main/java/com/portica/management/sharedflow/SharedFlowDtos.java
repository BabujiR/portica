package com.portica.management.sharedflow;

import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response payloads for shared flows. */
public final class SharedFlowDtos {

    private SharedFlowDtos() {
    }

    public record CreateSharedFlowRevisionRequest(
            @Size(max = 1024) String description,
            @Schema(description = "Policies used by the steps") List<PolicyDefinition> policies,
            @Schema(description = "Steps run, in order, when a FlowCallout calls this shared flow") List<FlowStep> steps) {
    }

    public record CreateSharedFlowRequest(
            @NotBlank @ResourceName @Schema(example = "security") String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @NotNull @Valid CreateSharedFlowRevisionRequest revision) {
    }

    public record SharedFlowResponse(UUID id, String name, String displayName, String description, int latestRevision,
                                     Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        static SharedFlowResponse from(SharedFlow f) {
            return new SharedFlowResponse(f.getId(), f.getName(), f.getDisplayName(), f.getDescription(),
                    f.getLatestRevision(), f.getCreatedAt(), f.getCreatedBy(), f.getUpdatedAt(), f.getUpdatedBy());
        }
    }

    public record SharedFlowRevisionResponse(String sharedFlow, int revision, String description,
                                             List<PolicyDefinition> policies, List<FlowStep> steps,
                                             Instant createdAt, String createdBy) {

        static SharedFlowRevisionResponse from(SharedFlowRevision r) {
            return new SharedFlowRevisionResponse(r.getSharedFlow().getName(), r.getRevision(), r.getDescription(),
                    r.policies(), r.steps(), r.getCreatedAt(), r.getCreatedBy());
        }
    }

    public record CreateSharedFlowResponse(SharedFlowResponse sharedFlow, SharedFlowRevisionResponse revision) {
    }

    public record SharedFlowDeploymentResponse(String environment, String sharedFlow, int revision,
                                               Integer previousRevision, Instant deployedAt, String deployedBy) {

        static SharedFlowDeploymentResponse from(SharedFlowDeployment d, Integer previous) {
            return new SharedFlowDeploymentResponse(d.getEnvironment().getName(), d.getSharedFlow().getName(),
                    d.getRevision().getRevision(), previous, d.getUpdatedAt(), d.getUpdatedBy());
        }
    }
}

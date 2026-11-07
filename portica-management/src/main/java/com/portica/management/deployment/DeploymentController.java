package com.portica.management.deployment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
@Tag(name = "Deployments", description = "Which API proxy revisions are live in which environments")
public class DeploymentController {

    private final DeploymentService deployments;

    public DeploymentController(DeploymentService deployments) {
        this.deployments = deployments;
    }

    @PostMapping("/environments/{env}/apis/{api}/revisions/{rev}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_DEPLOY')")
    @Operation(summary = "Deploy a revision to an environment",
            description = "Replaces any other live revision of the same proxy without downtime. "
                    + "Returns 201 when something changed and 200 if that revision was already live.")
    public ResponseEntity<DeploymentResponse> deploy(@PathVariable String org, @PathVariable String env,
                                                     @PathVariable String api, @PathVariable int rev) {
        DeploymentService.DeploymentOutcome outcome = deployments.deploy(org, env, api, rev);
        return ResponseEntity.status(outcome.changed() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(DeploymentResponse.from(org, env, outcome.deployment(), outcome.previousRevision()));
    }

    @DeleteMapping("/environments/{env}/apis/{api}/revisions/{rev}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_DEPLOY')")
    @Operation(summary = "Undeploy a revision from an environment")
    public ResponseEntity<Void> undeploy(@PathVariable String org, @PathVariable String env,
                                         @PathVariable String api, @PathVariable int rev) {
        deployments.undeploy(org, env, api, rev);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/environments/{env}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List what is live in an environment")
    public List<DeploymentResponse> listForEnvironment(@PathVariable String org, @PathVariable String env) {
        return deployments.listForEnvironment(org, env).stream()
                .map(d -> DeploymentResponse.from(org, env, d, null))
                .toList();
    }

    @GetMapping("/apis/{api}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List the environments where an API proxy is live")
    public List<DeploymentResponse> listForProxy(@PathVariable String org, @PathVariable String api) {
        return deployments.listForProxy(org, api).stream()
                .map(d -> DeploymentResponse.from(org, d.getEnvironment().getName(), d, null))
                .toList();
    }

    /**
     * @param gatewayPath      where clients call it on the gateway: {@code /{org}/{env}{basePath}}
     * @param previousRevision the revision this deployment replaced, when it replaced one
     */
    public record DeploymentResponse(String environment, String apiProxy, int revision, String basePath,
                                     String gatewayPath, Integer previousRevision,
                                     Instant deployedAt, String deployedBy) {

        static DeploymentResponse from(String org, String env, Deployment d, Integer previousRevision) {
            return new DeploymentResponse(env, d.getApiProxy().getName(), d.getRevision().getRevision(),
                    d.getBasePath(), "/" + org + "/" + env + d.getBasePath(), previousRevision,
                    d.getUpdatedAt(), d.getUpdatedBy());
        }
    }
}

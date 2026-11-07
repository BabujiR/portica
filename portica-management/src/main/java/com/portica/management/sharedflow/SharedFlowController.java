package com.portica.management.sharedflow;

import com.portica.common.api.PageResponse;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import com.portica.management.sharedflow.SharedFlowDtos.CreateSharedFlowRequest;
import com.portica.management.sharedflow.SharedFlowDtos.CreateSharedFlowResponse;
import com.portica.management.sharedflow.SharedFlowDtos.CreateSharedFlowRevisionRequest;
import com.portica.management.sharedflow.SharedFlowDtos.SharedFlowDeploymentResponse;
import com.portica.management.sharedflow.SharedFlowDtos.SharedFlowResponse;
import com.portica.management.sharedflow.SharedFlowDtos.SharedFlowRevisionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
@Tag(name = "Shared flows", description = "Reusable policy chains called from API proxies with FlowCallout")
public class SharedFlowController {

    private static final Set<String> SORTS = Set.of("name", "displayName", "createdAt", "updatedAt");

    private final SharedFlowService sharedFlows;

    public SharedFlowController(SharedFlowService sharedFlows) {
        this.sharedFlows = sharedFlows;
    }

    @GetMapping("/sharedflows")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List shared flows")
    public PageResponse<SharedFlowResponse> list(@PathVariable String org, @RequestParam(required = false) String q,
                                                 @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        return PageResponses.from(sharedFlows.list(org, q, PageRequests.requireSortable(pageable, SORTS)),
                SharedFlowResponse::from);
    }

    @GetMapping("/sharedflows/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get a shared flow")
    public SharedFlowResponse get(@PathVariable String org, @PathVariable String name) {
        return SharedFlowResponse.from(sharedFlows.get(org, name));
    }

    @PostMapping("/sharedflows")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create a shared flow with its first revision")
    public ResponseEntity<CreateSharedFlowResponse> create(@PathVariable String org,
                                                           @Valid @RequestBody CreateSharedFlowRequest request) {
        SharedFlowRevision revision = sharedFlows.create(org, request);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/sharedflows/{name}")
                        .buildAndExpand(org, request.name()).toUri())
                .body(new CreateSharedFlowResponse(SharedFlowResponse.from(revision.getSharedFlow()),
                        SharedFlowRevisionResponse.from(revision)));
    }

    @DeleteMapping("/sharedflows/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete a shared flow and its revisions", description = "Refused while deployed.")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String name) {
        sharedFlows.delete(org, name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sharedflows/{name}/revisions")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List revisions, newest first")
    public PageResponse<SharedFlowRevisionResponse> listRevisions(@PathVariable String org, @PathVariable String name,
                                                                  @PageableDefault(size = 20) Pageable pageable) {
        return PageResponses.from(sharedFlows.listRevisions(org, name,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())), SharedFlowRevisionResponse::from);
    }

    @PostMapping("/sharedflows/{name}/revisions")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create a new revision")
    public ResponseEntity<SharedFlowRevisionResponse> createRevision(@PathVariable String org, @PathVariable String name,
                                                                     @Valid @RequestBody CreateSharedFlowRevisionRequest request) {
        SharedFlowRevision revision = sharedFlows.createRevision(org, name, request);
        return ResponseEntity.created(UriComponentsBuilder
                        .fromPath("/api/v1/organizations/{org}/sharedflows/{name}/revisions/{rev}")
                        .buildAndExpand(org, name, revision.getRevision()).toUri())
                .body(SharedFlowRevisionResponse.from(revision));
    }

    @GetMapping("/sharedflows/{name}/revisions/{rev}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get a revision")
    public SharedFlowRevisionResponse getRevision(@PathVariable String org, @PathVariable String name,
                                                  @PathVariable int rev) {
        return SharedFlowRevisionResponse.from(sharedFlows.getRevision(org, name, rev));
    }

    @DeleteMapping("/sharedflows/{name}/revisions/{rev}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete a revision", description = "Refused while that revision is deployed.")
    public ResponseEntity<Void> deleteRevision(@PathVariable String org, @PathVariable String name,
                                               @PathVariable int rev) {
        sharedFlows.deleteRevision(org, name, rev);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/environments/{env}/sharedflows/{name}/revisions/{rev}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_DEPLOY')")
    @Operation(summary = "Deploy a shared flow revision to an environment",
            description = "Every shared flow it calls must already be deployed there. 201 if changed, 200 if already live.")
    public ResponseEntity<SharedFlowDeploymentResponse> deploy(@PathVariable String org, @PathVariable String env,
                                                               @PathVariable String name, @PathVariable int rev) {
        SharedFlowService.DeployOutcome outcome = sharedFlows.deploy(org, env, name, rev);
        return ResponseEntity.status(outcome.changed() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SharedFlowDeploymentResponse.from(outcome.deployment(), outcome.previousRevision()));
    }

    @DeleteMapping("/environments/{env}/sharedflows/{name}/revisions/{rev}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_DEPLOY')")
    @Operation(summary = "Undeploy a shared flow revision", description = "Refused while a deployed API proxy or shared flow calls it.")
    public ResponseEntity<Void> undeploy(@PathVariable String org, @PathVariable String env,
                                         @PathVariable String name, @PathVariable int rev) {
        sharedFlows.undeploy(org, env, name, rev);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/environments/{env}/sharedflow-deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List shared flows live in an environment")
    public List<SharedFlowDeploymentResponse> listForEnvironment(@PathVariable String org, @PathVariable String env) {
        return sharedFlows.listForEnvironment(org, env).stream()
                .map(d -> SharedFlowDeploymentResponse.from(d, null)).toList();
    }

    @GetMapping("/sharedflows/{name}/deployments")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List the environments where a shared flow is live")
    public List<SharedFlowDeploymentResponse> listForSharedFlow(@PathVariable String org, @PathVariable String name) {
        return sharedFlows.listForSharedFlow(org, name).stream()
                .map(d -> SharedFlowDeploymentResponse.from(d, null)).toList();
    }
}

package com.portica.management.environment;

import com.portica.common.api.PageResponse;
import com.portica.management.environment.EnvironmentDtos.CreateEnvironmentRequest;
import com.portica.management.environment.EnvironmentDtos.EnvironmentResponse;
import com.portica.management.environment.EnvironmentDtos.UpdateEnvironmentRequest;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/organizations/{org}/environments")
@Tag(name = "Environments", description = "Deployment targets inside an organization")
public class EnvironmentController {

    private final EnvironmentService environments;

    public EnvironmentController(EnvironmentService environments) {
        this.environments = environments;
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_READ')")
    @Operation(summary = "List environments", description = "Server-side paging and search on name/display name.")
    public PageResponse<EnvironmentResponse> list(@PathVariable String org,
                                                  @RequestParam(required = false) String q,
                                                  @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                  Pageable pageable) {
        Pageable checked = PageRequests.requireSortable(pageable, PageRequests.STANDARD_SORTS);
        return PageResponses.from(environments.list(org, q, checked), EnvironmentResponse::from);
    }

    @GetMapping("/{env}")
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_READ')")
    @Operation(summary = "Get an environment")
    public EnvironmentResponse get(@PathVariable String org, @PathVariable String env) {
        return EnvironmentResponse.from(environments.get(org, env));
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_CREATE')")
    @Operation(summary = "Create an environment")
    public ResponseEntity<EnvironmentResponse> create(@PathVariable String org,
                                                      @Valid @RequestBody CreateEnvironmentRequest request) {
        Environment created = environments.create(org, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{env}").buildAndExpand(created.getName()).toUri();
        return ResponseEntity.created(location).body(EnvironmentResponse.from(created));
    }

    @PatchMapping("/{env}")
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_UPDATE')")
    @Operation(summary = "Update an environment's display name or description")
    public EnvironmentResponse update(@PathVariable String org, @PathVariable String env,
                                      @Valid @RequestBody UpdateEnvironmentRequest request) {
        return EnvironmentResponse.from(environments.update(org, env, request));
    }

    @DeleteMapping("/{env}")
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_DELETE')")
    @Operation(summary = "Delete an environment")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String env) {
        environments.delete(org, env);
        return ResponseEntity.noContent().build();
    }
}

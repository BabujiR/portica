package com.portica.management.targetserver;

import com.portica.common.api.PageResponse;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import com.portica.management.targetserver.TargetServerDtos.CreateTargetServerRequest;
import com.portica.management.targetserver.TargetServerDtos.TargetServerRequest;
import com.portica.management.targetserver.TargetServerDtos.TargetServerResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/{org}/environments/{env}/target-servers")
@Tag(name = "Target servers", description = "Named backend endpoints, defined per environment")
public class TargetServerController {

    private static final Set<String> SORTS = Set.of("name", "host", "createdAt", "updatedAt");

    private final TargetServerService targetServers;

    public TargetServerController(TargetServerService targetServers) {
        this.targetServers = targetServers;
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_READ')")
    @Operation(summary = "List target servers")
    public PageResponse<TargetServerResponse> list(@PathVariable String org, @PathVariable String env,
                                                   @RequestParam(required = false) String q,
                                                   @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                   Pageable pageable) {
        return PageResponses.from(targetServers.list(org, env, q, PageRequests.requireSortable(pageable, SORTS)),
                TargetServerResponse::from);
    }

    @GetMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'ENV_READ')")
    @Operation(summary = "Get a target server")
    public TargetServerResponse get(@PathVariable String org, @PathVariable String env, @PathVariable String name) {
        return TargetServerResponse.from(targetServers.get(org, env, name));
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(#org, 'TARGET_SERVER_WRITE')")
    @Operation(summary = "Create a target server")
    public ResponseEntity<TargetServerResponse> create(@PathVariable String org, @PathVariable String env,
                                                       @Valid @RequestBody CreateTargetServerRequest request) {
        TargetServer created = targetServers.create(org, env, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{name}").buildAndExpand(created.getName()).toUri();
        return ResponseEntity.created(location).body(TargetServerResponse.from(created));
    }

    @PutMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'TARGET_SERVER_WRITE')")
    @Operation(summary = "Replace a target server's settings",
            description = "Deployed proxies that use it are re-routed on the next gateway sync, without redeploying.")
    public TargetServerResponse update(@PathVariable String org, @PathVariable String env, @PathVariable String name,
                                       @Valid @RequestBody TargetServerRequest request) {
        return TargetServerResponse.from(targetServers.update(org, env, name, request));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'TARGET_SERVER_WRITE')")
    @Operation(summary = "Delete a target server", description = "Refused while a deployed proxy uses it.")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String env, @PathVariable String name) {
        targetServers.delete(org, env, name);
        return ResponseEntity.noContent().build();
    }
}

package com.portica.management.kvm;

import com.portica.common.api.PageResponse;
import com.portica.management.kvm.KeyValueMapDtos.CreateKeyValueMapRequest;
import com.portica.management.kvm.KeyValueMapDtos.EntryResponse;
import com.portica.management.kvm.KeyValueMapDtos.KeyValueMapResponse;
import com.portica.management.kvm.KeyValueMapDtos.PutEntryRequest;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Key value maps, at organization scope ({@code /keyvaluemaps}) and environment scope
 * ({@code /environments/{env}/keyvaluemaps}). Reading needs PROXY_READ, writing PROXY_WRITE;
 * values of encrypted maps are always masked.
 */
@RestController
@RequestMapping("/api/v1/organizations/{org}")
@Tag(name = "Key value maps", description = "Runtime configuration and secrets read by the KeyValueMapOperations policy")
public class KeyValueMapController {

    private final KeyValueMapService kvms;

    public KeyValueMapController(KeyValueMapService kvms) {
        this.kvms = kvms;
    }

    // ----------------------------------------------------------------- organization scope

    @GetMapping("/keyvaluemaps")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List organization-scoped key value maps")
    public List<KeyValueMapResponse> listOrg(@PathVariable String org) {
        return kvms.list(org, null);
    }

    @PostMapping("/keyvaluemaps")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create an organization-scoped key value map")
    public ResponseEntity<KeyValueMapResponse> createOrg(@PathVariable String org,
                                                         @Valid @RequestBody CreateKeyValueMapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(kvms.create(org, null, request));
    }

    @GetMapping("/keyvaluemaps/{map}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get an organization-scoped key value map")
    public KeyValueMapResponse getOrg(@PathVariable String org, @PathVariable String map) {
        return kvms.get(org, null, map);
    }

    @DeleteMapping("/keyvaluemaps/{map}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete an organization-scoped key value map and its entries")
    public ResponseEntity<Void> deleteOrg(@PathVariable String org, @PathVariable String map) {
        kvms.delete(org, null, map);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keyvaluemaps/{map}/entries")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List entries (encrypted values are masked)")
    public PageResponse<EntryResponse> listOrgEntries(@PathVariable String org, @PathVariable String map,
                                                      @PageableDefault(size = 50) Pageable pageable) {
        return PageResponses.from(kvms.listEntries(org, null, map, unsorted(pageable)), e -> e);
    }

    @GetMapping("/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get an entry (encrypted values are masked)")
    public EntryResponse getOrgEntry(@PathVariable String org, @PathVariable String map, @PathVariable String key) {
        return kvms.getEntry(org, null, map, key);
    }

    @PutMapping("/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create or replace an entry")
    public EntryResponse putOrgEntry(@PathVariable String org, @PathVariable String map, @PathVariable String key,
                                     @Valid @RequestBody PutEntryRequest request) {
        return kvms.putEntry(org, null, map, key, request.value());
    }

    @DeleteMapping("/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete an entry")
    public ResponseEntity<Void> deleteOrgEntry(@PathVariable String org, @PathVariable String map,
                                               @PathVariable String key) {
        kvms.deleteEntry(org, null, map, key);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- environment scope

    @GetMapping("/environments/{env}/keyvaluemaps")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List an environment's key value maps")
    public List<KeyValueMapResponse> listEnv(@PathVariable String org, @PathVariable String env) {
        return kvms.list(org, env);
    }

    @PostMapping("/environments/{env}/keyvaluemaps")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create an environment-scoped key value map")
    public ResponseEntity<KeyValueMapResponse> createEnv(@PathVariable String org, @PathVariable String env,
                                                         @Valid @RequestBody CreateKeyValueMapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(kvms.create(org, env, request));
    }

    @GetMapping("/environments/{env}/keyvaluemaps/{map}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get an environment-scoped key value map")
    public KeyValueMapResponse getEnv(@PathVariable String org, @PathVariable String env, @PathVariable String map) {
        return kvms.get(org, env, map);
    }

    @DeleteMapping("/environments/{env}/keyvaluemaps/{map}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete an environment-scoped key value map and its entries")
    public ResponseEntity<Void> deleteEnv(@PathVariable String org, @PathVariable String env, @PathVariable String map) {
        kvms.delete(org, env, map);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/environments/{env}/keyvaluemaps/{map}/entries")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List entries (encrypted values are masked)")
    public PageResponse<EntryResponse> listEnvEntries(@PathVariable String org, @PathVariable String env,
                                                      @PathVariable String map,
                                                      @PageableDefault(size = 50) Pageable pageable) {
        return PageResponses.from(kvms.listEntries(org, env, map, unsorted(pageable)), e -> e);
    }

    @GetMapping("/environments/{env}/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get an entry (encrypted values are masked)")
    public EntryResponse getEnvEntry(@PathVariable String org, @PathVariable String env, @PathVariable String map,
                                     @PathVariable String key) {
        return kvms.getEntry(org, env, map, key);
    }

    @PutMapping("/environments/{env}/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create or replace an entry")
    public EntryResponse putEnvEntry(@PathVariable String org, @PathVariable String env, @PathVariable String map,
                                     @PathVariable String key, @Valid @RequestBody PutEntryRequest request) {
        return kvms.putEntry(org, env, map, key, request.value());
    }

    @DeleteMapping("/environments/{env}/keyvaluemaps/{map}/entries/{key}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete an entry")
    public ResponseEntity<Void> deleteEnvEntry(@PathVariable String org, @PathVariable String env,
                                               @PathVariable String map, @PathVariable String key) {
        kvms.deleteEntry(org, env, map, key);
        return ResponseEntity.noContent().build();
    }

    private static Pageable unsorted(Pageable p) {
        return PageRequest.of(p.getPageNumber(), p.getPageSize());
    }
}

package com.portica.management.proxy;

import com.portica.common.api.PageResponse;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.proxy.ApiProxyDtos.ApiProxyResponse;
import com.portica.management.proxy.ApiProxyDtos.CreateApiProxyRequest;
import com.portica.management.proxy.ApiProxyDtos.CreateApiProxyResponse;
import com.portica.management.proxy.ApiProxyDtos.CreateRevisionRequest;
import com.portica.management.proxy.ApiProxyDtos.ImportOpenApiRequest;
import com.portica.management.proxy.ApiProxyDtos.ImportResult;
import com.portica.management.proxy.ApiProxyDtos.OperationResponse;
import com.portica.management.proxy.ApiProxyDtos.OperationsResponse;
import com.portica.management.proxy.ApiProxyDtos.RevisionResponse;
import com.portica.management.proxy.ApiProxyDtos.UpdateApiProxyRequest;
import com.portica.management.proxy.openapi.OpenApiDocument;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/{org}/apis")
@Tag(name = "API proxies", description = "API proxies and their immutable revisions")
public class ApiProxyController {

    private static final Set<String> SORTS = Set.of("name", "displayName", "createdAt", "updatedAt");

    private final ApiProxyService proxies;

    public ApiProxyController(ApiProxyService proxies) {
        this.proxies = proxies;
    }

    // ----------------------------------------------------------------- proxies

    @GetMapping
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List API proxies")
    public PageResponse<ApiProxyResponse> list(@PathVariable String org, @RequestParam(required = false) String q,
                                               @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                               Pageable pageable) {
        return PageResponses.from(proxies.list(org, q, PageRequests.requireSortable(pageable, SORTS)),
                ApiProxyResponse::from);
    }

    @GetMapping("/{api}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get an API proxy")
    public ApiProxyResponse get(@PathVariable String org, @PathVariable String api) {
        return ApiProxyResponse.from(proxies.get(org, api));
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create an API proxy with its first revision")
    public ResponseEntity<CreateApiProxyResponse> create(@PathVariable String org,
                                                         @Valid @RequestBody CreateApiProxyRequest request) {
        ApiProxyRevision revision = proxies.create(org, request);
        return ResponseEntity.created(proxyUri(org, revision.getApiProxy().getName()))
                .body(new CreateApiProxyResponse(ApiProxyResponse.from(revision.getApiProxy()),
                        RevisionResponse.from(revision)));
    }

    @PostMapping("/import")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create an API proxy (or a new revision) from an OpenAPI 3.x / Swagger 2.0 document",
            description = "Re-importing into an existing proxy creates a new revision with the updated spec.")
    public ResponseEntity<ImportResult> importOpenApi(@PathVariable String org,
                                                      @Valid @RequestBody ImportOpenApiRequest request) {
        ApiProxyService.ImportOutcome outcome = proxies.importOpenApi(org, request);
        ApiProxyRevision revision = outcome.revision();
        ImportResult result = new ImportResult(ApiProxyResponse.from(revision.getApiProxy()),
                RevisionResponse.from(revision), outcome.createdApiProxy(), outcome.document().specVersion(),
                outcome.document().apiVersion(), outcome.document().operations().size());
        return ResponseEntity.created(revisionUri(org, revision.getApiProxy().getName(), revision.getRevision()))
                .body(result);
    }

    @PatchMapping("/{api}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Update an API proxy's display name or description")
    public ApiProxyResponse update(@PathVariable String org, @PathVariable String api,
                                   @Valid @RequestBody UpdateApiProxyRequest request) {
        return ApiProxyResponse.from(proxies.update(org, api, request));
    }

    @DeleteMapping("/{api}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete an API proxy and all its revisions", description = "Refused while deployed.")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String api) {
        proxies.delete(org, api);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- revisions

    @GetMapping("/{api}/revisions")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List revisions, newest first")
    public PageResponse<RevisionResponse> listRevisions(@PathVariable String org, @PathVariable String api,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return PageResponses.from(proxies.listRevisions(org, api, unsorted), RevisionResponse::from);
    }

    @PostMapping("/{api}/revisions")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Create a new revision", description = "Revisions are immutable; this is how you change a proxy.")
    public ResponseEntity<RevisionResponse> createRevision(@PathVariable String org, @PathVariable String api,
                                                           @Valid @RequestBody CreateRevisionRequest request) {
        ApiProxyRevision revision = proxies.createRevision(org, api, request);
        return ResponseEntity.created(revisionUri(org, api, revision.getRevision()))
                .body(RevisionResponse.from(revision));
    }

    @GetMapping("/{api}/revisions/{rev}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Get a revision")
    public RevisionResponse getRevision(@PathVariable String org, @PathVariable String api, @PathVariable int rev) {
        return RevisionResponse.from(proxies.getRevision(org, api, rev));
    }

    @GetMapping(value = "/{api}/revisions/{rev}/openapi", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "Download the revision's OpenAPI document as it was imported")
    public ResponseEntity<String> getOpenApiSpec(@PathVariable String org, @PathVariable String api,
                                                 @PathVariable int rev) {
        String spec = proxies.getRevision(org, api, rev).getOpenApiSpec();
        if (spec == null) {
            throw new ResourceNotFoundException("OpenAPI spec of revision", api + "/" + rev);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(spec);
    }

    @GetMapping("/{api}/revisions/{rev}/operations")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_READ')")
    @Operation(summary = "List the operations described by the revision's OpenAPI document")
    public OperationsResponse getOperations(@PathVariable String org, @PathVariable String api,
                                            @PathVariable int rev) {
        OpenApiDocument document = proxies.describeRevision(org, api, rev)
                .orElseThrow(() -> new ResourceNotFoundException("OpenAPI spec of revision", api + "/" + rev));
        return new OperationsResponse(document.specVersion(), document.title(), document.apiVersion(),
                document.operations().stream()
                        .map(o -> new OperationResponse(o.method(), o.path(), o.operationId(), o.summary()))
                        .toList());
    }

    @DeleteMapping("/{api}/revisions/{rev}")
    @PreAuthorize("@authz.hasPermission(#org, 'PROXY_WRITE')")
    @Operation(summary = "Delete a revision", description = "Refused while that revision is deployed.")
    public ResponseEntity<Void> deleteRevision(@PathVariable String org, @PathVariable String api,
                                               @PathVariable int rev) {
        proxies.deleteRevision(org, api, rev);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- helpers

    private static URI proxyUri(String org, String api) {
        return UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/apis/{api}").buildAndExpand(org, api).toUri();
    }

    private static URI revisionUri(String org, String api, int rev) {
        return UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/apis/{api}/revisions/{rev}")
                .buildAndExpand(org, api, rev).toUri();
    }
}

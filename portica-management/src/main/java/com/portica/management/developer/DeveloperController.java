package com.portica.management.developer;

import com.portica.common.api.PageResponse;
import com.portica.management.developer.DeveloperDtos.AddProductRequest;
import com.portica.management.developer.DeveloperDtos.AppResponse;
import com.portica.management.developer.DeveloperDtos.CreateAppRequest;
import com.portica.management.developer.DeveloperDtos.CreateAppResponse;
import com.portica.management.developer.DeveloperDtos.CreateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.CreateDeveloperRequest;
import com.portica.management.developer.DeveloperDtos.CredentialResponse;
import com.portica.management.developer.DeveloperDtos.DeveloperResponse;
import com.portica.management.developer.DeveloperDtos.IssuedCredential;
import com.portica.management.developer.DeveloperDtos.IssuedCredentialResponse;
import com.portica.management.developer.DeveloperDtos.RotateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.RotatedCredentialResponse;
import com.portica.management.developer.DeveloperDtos.UpdateAppRequest;
import com.portica.management.developer.DeveloperDtos.UpdateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.UpdateDeveloperRequest;
import com.portica.management.developer.DeveloperDtos.UpdateGrantRequest;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
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
import java.util.UUID;

/**
 * Developers, developer apps and app credentials (consumer key/secret). Responses that carry a plain
 * secret are marked {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/organizations/{org}")
@Tag(name = "Developers and apps", description = "API consumers, their apps and credentials")
public class DeveloperController {

    private static final Set<String> DEVELOPER_SORTS = Set.of("email", "firstName", "lastName", "createdAt", "updatedAt");
    private static final Set<String> APP_SORTS = Set.of("name", "displayName", "createdAt", "updatedAt");

    private final DeveloperService developers;

    public DeveloperController(DeveloperService developers) {
        this.developers = developers;
    }

    // ----------------------------------------------------------------- developers

    @GetMapping("/developers")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "List developers")
    public PageResponse<DeveloperResponse> list(@PathVariable String org, @RequestParam(required = false) String q,
                                                @PageableDefault(size = 20, sort = "email", direction = Sort.Direction.ASC)
                                                Pageable pageable) {
        return PageResponses.from(developers.list(org, q, PageRequests.requireSortable(pageable, DEVELOPER_SORTS)),
                DeveloperResponse::from);
    }

    @GetMapping("/developers/{email}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "Get a developer")
    public DeveloperResponse get(@PathVariable String org, @PathVariable String email) {
        return DeveloperResponse.from(developers.get(org, email));
    }

    @PostMapping("/developers")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Register a developer")
    public ResponseEntity<DeveloperResponse> create(@PathVariable String org,
                                                    @Valid @RequestBody CreateDeveloperRequest request) {
        Developer developer = developers.create(org, request);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/developers/{email}")
                .buildAndExpand(org, developer.getEmail()).toUri()).body(DeveloperResponse.from(developer));
    }

    @PatchMapping("/developers/{email}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Update a developer", description = "status INACTIVE stops all of the developer's apps.")
    public DeveloperResponse update(@PathVariable String org, @PathVariable String email,
                                    @Valid @RequestBody UpdateDeveloperRequest request) {
        return DeveloperResponse.from(developers.update(org, email, request));
    }

    @DeleteMapping("/developers/{email}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Delete a developer with all apps and credentials")
    public ResponseEntity<Void> delete(@PathVariable String org, @PathVariable String email) {
        developers.delete(org, email);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- apps

    @GetMapping("/apps")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "List every app of the organization", description = "q matches app name or developer email.")
    public PageResponse<AppResponse> listAllApps(@PathVariable String org, @RequestParam(required = false) String q,
                                                 @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        return appPage(developers.searchApps(org, q, PageRequests.requireSortable(pageable, APP_SORTS)));
    }

    @GetMapping("/developers/{email}/apps")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "List a developer's apps")
    public PageResponse<AppResponse> listApps(@PathVariable String org, @PathVariable String email,
                                              @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                              Pageable pageable) {
        return appPage(developers.listApps(org, email, PageRequests.requireSortable(pageable, APP_SORTS)));
    }

    @GetMapping("/developers/{email}/apps/{app}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "Get an app with its credentials (keys are shown by prefix only)")
    public AppResponse getApp(@PathVariable String org, @PathVariable String email, @PathVariable String app) {
        return appResponse(developers.getApp(org, email, app));
    }

    @PostMapping("/developers/{email}/apps")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Register an app and issue its first credential",
            description = "The response is the only time the consumer secret is shown.")
    public ResponseEntity<CreateAppResponse> createApp(@PathVariable String org, @PathVariable String email,
                                                       @Valid @RequestBody CreateAppRequest request) {
        IssuedCredential issued = developers.createApp(org, email, request);
        DeveloperApp app = issued.credential().getApp();
        URI location = UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/developers/{email}/apps/{app}")
                .buildAndExpand(org, app.getDeveloper().getEmail(), app.getName()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore())
                .body(new CreateAppResponse(appResponse(app), IssuedCredentialResponse.from(issued)));
    }

    @PatchMapping("/developers/{email}/apps/{app}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Update an app", description = "status REVOKED stops every credential of the app.")
    public AppResponse updateApp(@PathVariable String org, @PathVariable String email, @PathVariable String app,
                                 @Valid @RequestBody UpdateAppRequest request) {
        return appResponse(developers.updateApp(org, email, app, request));
    }

    @DeleteMapping("/developers/{email}/apps/{app}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Delete an app and its credentials")
    public ResponseEntity<Void> deleteApp(@PathVariable String org, @PathVariable String email, @PathVariable String app) {
        developers.deleteApp(org, email, app);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- credentials

    @PostMapping("/developers/{email}/apps/{app}/keys")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Issue an additional credential", description = "The secret is shown only in this response.")
    public ResponseEntity<IssuedCredentialResponse> createKey(@PathVariable String org, @PathVariable String email,
                                                              @PathVariable String app,
                                                              @Valid @RequestBody CreateCredentialRequest request) {
        IssuedCredential issued = developers.createCredential(org, email, app, request);
        return ResponseEntity.created(keyLocation(org, email, app, issued.credential().getId()))
                .cacheControl(CacheControl.noStore()).body(IssuedCredentialResponse.from(issued));
    }

    @GetMapping("/developers/{email}/apps/{app}/keys/{keyId}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_READ')")
    @Operation(summary = "Get a credential")
    public CredentialResponse getKey(@PathVariable String org, @PathVariable String email, @PathVariable String app,
                                     @PathVariable UUID keyId) {
        return CredentialResponse.from(developers.getCredential(org, email, app, keyId), developers.now());
    }

    @PatchMapping("/developers/{email}/apps/{app}/keys/{keyId}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Revoke, re-approve or set the expiry of a credential")
    public CredentialResponse updateKey(@PathVariable String org, @PathVariable String email, @PathVariable String app,
                                        @PathVariable UUID keyId, @Valid @RequestBody UpdateCredentialRequest request) {
        return CredentialResponse.from(developers.updateCredential(org, email, app, keyId, request), developers.now());
    }

    @PostMapping("/developers/{email}/apps/{app}/keys/{keyId}/rotate")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Rotate a credential",
            description = "Issues a new credential with the same API products; the old one keeps working for graceSeconds.")
    public ResponseEntity<RotatedCredentialResponse> rotateKey(@PathVariable String org, @PathVariable String email,
                                                               @PathVariable String app, @PathVariable UUID keyId,
                                                               @Valid @RequestBody(required = false) RotateCredentialRequest request) {
        DeveloperService.RotationResult result = developers.rotateCredential(org, email, app, keyId, request);
        return ResponseEntity.created(keyLocation(org, email, app, result.issued().credential().getId()))
                .cacheControl(CacheControl.noStore())
                .body(new RotatedCredentialResponse(IssuedCredentialResponse.from(result.issued()),
                        CredentialResponse.from(result.previous(), developers.now())));
    }

    @DeleteMapping("/developers/{email}/apps/{app}/keys/{keyId}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Delete a credential")
    public ResponseEntity<Void> deleteKey(@PathVariable String org, @PathVariable String email, @PathVariable String app,
                                          @PathVariable UUID keyId) {
        developers.deleteCredential(org, email, app, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/developers/{email}/apps/{app}/keys/{keyId}/apiproducts")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Add an API product to a credential",
            description = "Approved immediately for AUTO products, PENDING for MANUAL ones.")
    public CredentialResponse addProduct(@PathVariable String org, @PathVariable String email, @PathVariable String app,
                                         @PathVariable UUID keyId, @Valid @RequestBody AddProductRequest request) {
        return CredentialResponse.from(developers.addProduct(org, email, app, keyId, request.apiProduct()),
                developers.now());
    }

    @PatchMapping("/developers/{email}/apps/{app}/keys/{keyId}/apiproducts/{product}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Approve or revoke an API product for a credential")
    public CredentialResponse setProductStatus(@PathVariable String org, @PathVariable String email,
                                               @PathVariable String app, @PathVariable UUID keyId,
                                               @PathVariable String product,
                                               @Valid @RequestBody UpdateGrantRequest request) {
        return CredentialResponse.from(developers.setProductStatus(org, email, app, keyId, product, request.status()),
                developers.now());
    }

    @DeleteMapping("/developers/{email}/apps/{app}/keys/{keyId}/apiproducts/{product}")
    @PreAuthorize("@authz.hasPermission(#org, 'DEVELOPER_WRITE')")
    @Operation(summary = "Remove an API product from a credential")
    public CredentialResponse removeProduct(@PathVariable String org, @PathVariable String email,
                                            @PathVariable String app, @PathVariable UUID keyId,
                                            @PathVariable String product) {
        return CredentialResponse.from(developers.removeProduct(org, email, app, keyId, product), developers.now());
    }

    // ----------------------------------------------------------------- helpers

    private AppResponse appResponse(DeveloperApp app) {
        return AppResponse.from(app, developers.credentialsOf(app), developers.now());
    }

    private PageResponse<AppResponse> appPage(Page<DeveloperApp> page) {
        return PageResponses.of(page, page.getContent().stream().map(this::appResponse).toList());
    }

    private static URI keyLocation(String org, String email, String app, UUID keyId) {
        return UriComponentsBuilder.fromPath("/api/v1/organizations/{org}/developers/{email}/apps/{app}/keys/{keyId}")
                .buildAndExpand(org, email, app, keyId).toUri();
    }
}

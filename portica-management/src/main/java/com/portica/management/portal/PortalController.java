package com.portica.management.portal;

import com.portica.management.developer.DeveloperDtos.AddCertificateRequest;
import com.portica.management.developer.DeveloperDtos.AppResponse;
import com.portica.management.developer.DeveloperDtos.CreateAppResponse;
import com.portica.management.developer.DeveloperDtos.CredentialResponse;
import com.portica.management.developer.DeveloperDtos.DeveloperResponse;
import com.portica.management.developer.DeveloperDtos.IssuedCredential;
import com.portica.management.developer.DeveloperDtos.IssuedCredentialResponse;
import com.portica.management.developer.DeveloperService;
import com.portica.management.portal.PortalDtos.ApiDocs;
import com.portica.management.portal.PortalDtos.Catalog;
import com.portica.management.portal.PortalDtos.McpTools;
import com.portica.management.portal.PortalDtos.PortalAppRequest;
import com.portica.management.portal.PortalDtos.PortalKeyRequest;
import com.portica.management.portal.PortalDtos.RegisterRequest;
import com.portica.management.portal.PortalDtos.Usage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal API behind the developer portal (portica-portal). Only the portal's service account may call
 * it; it passes the signed-in developer's verified e-mail address in {@value #DEVELOPER_HEADER}, and every
 * {@code /me} operation is confined to that developer's own apps and keys.
 */
@RestController
@RequestMapping("/internal/v1/portal/organizations/{org}")
@PreAuthorize("@authz.isPortalClient()")
@Tag(name = "Developer portal (internal)", description = "Called by portica-portal on behalf of a developer")
public class PortalController {

    public static final String DEVELOPER_HEADER = "X-Portica-Developer";

    private final PortalService portal;
    private final DeveloperService developers;

    public PortalController(PortalService portal, DeveloperService developers) {
        this.portal = portal;
        this.developers = developers;
    }

    @GetMapping("/catalog")
    @Operation(summary = "Public API products, APIs and MCP servers")
    public Catalog catalog(@PathVariable String org) {
        return portal.catalog(org);
    }

    @GetMapping("/apis/{api}/environments/{env}/docs")
    @Operation(summary = "Operations and OpenAPI document of the revision live in the environment")
    public ApiDocs docs(@PathVariable String org, @PathVariable String api, @PathVariable String env) {
        return portal.docs(org, api, env);
    }

    @GetMapping("/mcp/{server}/environments/{env}/tools")
    @Operation(summary = "Tools of an MCP server as clients see them in the environment")
    public McpTools tools(@PathVariable String org, @PathVariable String server, @PathVariable String env) {
        return portal.mcpTools(org, server, env);
    }

    // ------------------------------------------------------------------ the developer

    @GetMapping("/me")
    public DeveloperResponse me(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email) {
        return DeveloperResponse.from(portal.me(org, email));
    }

    @PostMapping("/me")
    @Operation(summary = "Self-registration as a developer of the organization")
    public ResponseEntity<DeveloperResponse> register(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                                                      @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DeveloperResponse.from(portal.register(org, email, request)));
    }

    @GetMapping("/me/apps")
    public List<AppResponse> apps(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email) {
        return portal.apps(org, email);
    }

    @GetMapping("/me/apps/{app}")
    public AppResponse app(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email, @PathVariable String app) {
        return portal.app(org, email, app);
    }

    @PostMapping("/me/apps")
    @Operation(summary = "Register an app with public API products", description = "The secret is shown only in this response.")
    public ResponseEntity<CreateAppResponse> createApp(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                                                       @Valid @RequestBody PortalAppRequest request) {
        IssuedCredential issued = portal.createApp(org, email, request);
        AppResponse app = portal.app(org, email, issued.credential().getApp().getName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new CreateAppResponse(app, IssuedCredentialResponse.from(issued)));
    }

    @DeleteMapping("/me/apps/{app}")
    public ResponseEntity<Void> deleteApp(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                                          @PathVariable String app) {
        portal.deleteApp(org, email, app);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/apps/{app}/keys")
    public ResponseEntity<IssuedCredentialResponse> createKey(@PathVariable String org,
                                                              @RequestHeader(DEVELOPER_HEADER) String email,
                                                              @PathVariable String app,
                                                              @Valid @RequestBody PortalKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(IssuedCredentialResponse.from(portal.createKey(org, email, app, request)));
    }

    @DeleteMapping("/me/apps/{app}/keys/{keyId}")
    public ResponseEntity<Void> deleteKey(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                                          @PathVariable String app, @PathVariable UUID keyId) {
        portal.deleteKey(org, email, app, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/apps/{app}/keys/{keyId}/certificates")
    public ResponseEntity<CredentialResponse> addCertificate(@PathVariable String org,
                                                             @RequestHeader(DEVELOPER_HEADER) String email,
                                                             @PathVariable String app, @PathVariable UUID keyId,
                                                             @Valid @RequestBody AddCertificateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CredentialResponse.from(
                portal.addCertificate(org, email, app, keyId, request.certificate()), developers.now()));
    }

    @DeleteMapping("/me/apps/{app}/keys/{keyId}/certificates/{fingerprint}")
    public CredentialResponse removeCertificate(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                                                @PathVariable String app, @PathVariable UUID keyId,
                                                @PathVariable String fingerprint) {
        return CredentialResponse.from(portal.removeCertificate(org, email, app, keyId, fingerprint), developers.now());
    }

    @GetMapping("/me/usage")
    @Operation(summary = "The developer's own traffic (default: the last hour)")
    public Usage usage(@PathVariable String org, @RequestHeader(DEVELOPER_HEADER) String email,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                       @RequestParam(required = false) String environment) {
        return portal.usage(org, email, from, to, environment);
    }
}

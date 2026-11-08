package com.portica.management.mcp;

import com.portica.management.mcp.McpDtos.AuthorizeResponse;
import com.portica.management.mcp.McpDtos.CreateMcpServerRequest;
import com.portica.management.mcp.McpDtos.McpServerResponse;
import com.portica.management.mcp.McpDtos.ToolView;
import com.portica.management.mcp.McpDtos.UpdateMcpServerRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** MCP servers (Phase 7) and the OAuth redirect endpoint for remote servers. */
@RestController
@Tag(name = "MCP", description = "MCP servers: tools from OpenAPI specs and fronted remote servers")
public class McpController {

    private final McpService mcp;
    private final McpOAuthService oauth;

    public McpController(McpService mcp, McpOAuthService oauth) {
        this.mcp = mcp;
        this.oauth = oauth;
    }

    @GetMapping("/api/v1/organizations/{org}/mcp/servers")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_READ')")
    @Operation(summary = "List MCP servers")
    public List<McpServerResponse> list(@PathVariable String org) {
        return mcp.list(org);
    }

    @PostMapping("/api/v1/organizations/{org}/mcp/servers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    @Operation(summary = "Create an MCP server",
            description = "Served at /{org}/{env}/mcp/{name} (Streamable HTTP). Grant apps access by listing "
                    + "gatewayProxy (mcp-{name}) in an API product.")
    public McpServerResponse create(@PathVariable String org, @Valid @RequestBody CreateMcpServerRequest request) {
        return mcp.create(org, request);
    }

    @GetMapping("/api/v1/organizations/{org}/mcp/servers/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_READ')")
    public McpServerResponse get(@PathVariable String org, @PathVariable String name) {
        return mcp.get(org, name);
    }

    @PutMapping("/api/v1/organizations/{org}/mcp/servers/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    public McpServerResponse update(@PathVariable String org, @PathVariable String name,
                                    @Valid @RequestBody UpdateMcpServerRequest request) {
        return mcp.update(org, name, request);
    }

    @DeleteMapping("/api/v1/organizations/{org}/mcp/servers/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    public void delete(@PathVariable String org, @PathVariable String name) {
        mcp.delete(org, name);
    }

    @GetMapping("/api/v1/organizations/{org}/mcp/servers/{name}/tools")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_READ')")
    @Operation(summary = "Tools clients will see",
            description = "OPENAPI servers: generated from the revision deployed in 'environment' (latest revision when "
                    + "omitted). REMOTE servers: listed by the server itself.")
    public List<ToolView> tools(@PathVariable String org, @PathVariable String name,
                                @RequestParam(required = false) String environment) {
        return mcp.tools(org, name, environment);
    }

    @PostMapping("/api/v1/organizations/{org}/mcp/servers/{name}/oauth/authorize")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    @Operation(summary = "Start signing in to a remote server's authorization server (authorization code + PKCE)",
            description = "Open authorizationUrl in the browser; the authorization server sends the browser back to "
                    + "/api/v1/mcp/oauth/callback, which stores the tokens and returns to the console.")
    public AuthorizeResponse authorize(@PathVariable String org, @PathVariable String name) {
        return mcp.authorize(org, name);
    }

    @PostMapping("/api/v1/organizations/{org}/mcp/servers/{name}/oauth/connect")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    @Operation(summary = "Fetch (client credentials) or refresh a token now, to check the OAuth settings")
    public McpServerResponse connect(@PathVariable String org, @PathVariable String name) {
        return mcp.connect(org, name);
    }

    @DeleteMapping("/api/v1/organizations/{org}/mcp/servers/{name}/oauth/token")
    @PreAuthorize("@authz.hasPermission(#org, 'MCP_WRITE')")
    @Operation(summary = "Forget stored OAuth tokens")
    public McpServerResponse disconnect(@PathVariable String org, @PathVariable String name) {
        return mcp.disconnect(org, name);
    }

    /** Where authorization servers send the browser back. Public: the state value is the credential. */
    @GetMapping("/api/v1/mcp/oauth/callback")
    @Operation(summary = "OAuth redirect URI for remote MCP servers (browser only)")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription) {
        String target = oauth.completeAuthorization(state, code, error, errorDescription);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}

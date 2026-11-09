package com.portica.management.runtime;

import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.mcp.McpOAuthService;
import com.portica.management.mcp.McpServer;
import com.portica.management.mcp.McpServerRepository;
import com.portica.management.organization.OrganizationFinder;
import org.springframework.stereotype.Component;

/** Hands portica-mcp the current access token of a remote MCP server. */
@Component
public class McpRuntimeTokens {

    private final OrganizationFinder organizations;
    private final McpServerRepository servers;
    private final McpOAuthService oauth;

    public McpRuntimeTokens(OrganizationFinder organizations, McpServerRepository servers, McpOAuthService oauth) {
        this.organizations = organizations;
        this.servers = servers;
        this.oauth = oauth;
    }

    public RuntimeDtos.RuntimeMcpToken token(String org, String name) {
        McpServer server = servers.findByOrganization_IdAndName(organizations.getActive(org).getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException("McpServer", name));
        McpOAuthService.AccessToken token = oauth.accessToken(server.getId());
        return new RuntimeDtos.RuntimeMcpToken(token.token(), token.expiresAt());
    }
}

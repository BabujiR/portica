package com.portica.management.shared;

import com.portica.common.error.ResourceConflictException;
import com.portica.management.ai.AiProxyRepository;
import com.portica.management.mcp.McpServerRepository;
import com.portica.management.proxy.ApiProxyRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI proxies and MCP servers reach traffic as gateway proxies named {@code ai-<name>} and
 * {@code mcp-<name>}; API products grant apps access by those names. This keeps them from
 * clashing with API proxies of the same name.
 */
@Component
public class GatewayNames {

    public static final String AI_PREFIX = "ai-";
    public static final String MCP_PREFIX = "mcp-";

    private final ApiProxyRepository apiProxies;
    private final AiProxyRepository aiProxies;
    private final McpServerRepository mcpServers;

    public GatewayNames(ApiProxyRepository apiProxies, AiProxyRepository aiProxies, McpServerRepository mcpServers) {
        this.apiProxies = apiProxies;
        this.aiProxies = aiProxies;
        this.mcpServers = mcpServers;
    }

    public static String forAiProxy(String name) {
        return AI_PREFIX + name;
    }

    public static String forMcpServer(String name) {
        return MCP_PREFIX + name;
    }

    /** Before creating an API proxy. */
    public void requireFreeForApiProxy(UUID organizationId, String name) {
        if (name.startsWith(AI_PREFIX) && aiProxies.existsByOrganization_IdAndName(organizationId,
                name.substring(AI_PREFIX.length()))) {
            throw new ResourceConflictException("'%s' is the gateway name of AI proxy '%s'; choose another name"
                    .formatted(name, name.substring(AI_PREFIX.length())));
        }
        if (name.startsWith(MCP_PREFIX) && mcpServers.existsByOrganization_IdAndName(organizationId,
                name.substring(MCP_PREFIX.length()))) {
            throw new ResourceConflictException("'%s' is the gateway name of MCP server '%s'; choose another name"
                    .formatted(name, name.substring(MCP_PREFIX.length())));
        }
    }

    /** Before creating an AI proxy or MCP server whose gateway name is {@code gatewayName}. */
    public void requireFreeGatewayName(UUID organizationId, String gatewayName) {
        if (apiProxies.existsByOrganization_IdAndName(organizationId, gatewayName)) {
            throw new ResourceConflictException("An API proxy named '%s' already exists; its name would clash at the gateway"
                    .formatted(gatewayName));
        }
    }
}

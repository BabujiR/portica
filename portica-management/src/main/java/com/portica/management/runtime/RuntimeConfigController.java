package com.portica.management.runtime;

import com.portica.management.runtime.RuntimeDtos.RuntimeEnvironmentConfig;
import com.portica.management.runtime.RuntimeDtos.RuntimeIndex;
import com.portica.management.trace.TraceDtos.RuntimeTraceSession;
import com.portica.management.trace.TraceSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal API polled by gateways (service accounts with the {@code portica-runtime} realm role).
 * Not intended for console or end-user clients.
 */
@RestController
@RequestMapping("/internal/v1/runtime")
@Tag(name = "Runtime (internal)", description = "Configuration feed for Portica gateways")
public class RuntimeConfigController {

    private final RuntimeConfigService runtime;
    private final TraceSessionService traces;
    private final McpRuntimeTokens mcpTokens;

    public RuntimeConfigController(RuntimeConfigService runtime, TraceSessionService traces, McpRuntimeTokens mcpTokens) {
        this.runtime = runtime;
        this.traces = traces;
        this.mcpTokens = mcpTokens;
    }

    @GetMapping("/organizations/{org}/environments/{env}/ai")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "AI gateway feed: providers (with API keys) and AI proxies served in the environment")
    public RuntimeDtos.RuntimeAiConfig ai(@PathVariable String org, @PathVariable String env) {
        return runtime.aiSnapshot(org, env);
    }

    @GetMapping("/organizations/{org}/environments/{env}/mcp")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "MCP feed: servers served in the environment, with generated tools")
    public RuntimeDtos.RuntimeMcpConfig mcp(@PathVariable String org, @PathVariable String env) {
        return runtime.mcpSnapshot(org, env);
    }

    @PostMapping("/organizations/{org}/mcp-servers/{name}/token")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "Current access token for a remote MCP server (refreshed or fetched as needed)")
    public RuntimeDtos.RuntimeMcpToken mcpToken(@PathVariable String org, @PathVariable String name) {
        return mcpTokens.token(org, name);
    }

    @GetMapping("/environments")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "Config version of every active environment",
            description = "Gateways poll this and fetch an environment's snapshot only when its version moves.")
    public RuntimeIndex index() {
        return runtime.index();
    }

    @GetMapping("/organizations/{org}/environments/{env}")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "Runtime snapshot of one environment: deployed proxies with resolved targets")
    public RuntimeEnvironmentConfig snapshot(@PathVariable String org, @PathVariable String env) {
        return runtime.snapshot(org, env);
    }

    @GetMapping("/trace-sessions")
    @PreAuthorize("@authz.canReadRuntimeConfig()")
    @Operation(summary = "Trace sessions active right now", description = "Gateways poll this every few seconds.")
    public List<RuntimeTraceSession> traceSessions() {
        return traces.active();
    }
}

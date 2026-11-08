package com.portica.management.analytics;

import com.portica.management.analytics.AiMcpAnalyticsService.AiSummary;
import com.portica.management.analytics.AiMcpAnalyticsService.AiTimeseries;
import com.portica.management.analytics.AiMcpAnalyticsService.AiTop;
import com.portica.management.analytics.AiMcpAnalyticsService.McpCall;
import com.portica.management.analytics.AiMcpAnalyticsService.McpSummary;
import com.portica.management.analytics.AiMcpAnalyticsService.McpTop;
import com.portica.management.organization.OrganizationFinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** AI gateway usage and cost, and MCP tool calls (Phase 7). Same window rules as the API analytics. */
@RestController
@RequestMapping("/api/v1/organizations/{org}/analytics")
@Tag(name = "Analytics")
public class AiMcpAnalyticsController {

    private final AiMcpAnalyticsService analytics;
    private final OrganizationFinder organizations;

    public AiMcpAnalyticsController(AiMcpAnalyticsService analytics, OrganizationFinder organizations) {
        this.analytics = analytics;
        this.organizations = organizations;
    }

    @GetMapping("/ai/summary")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "AI calls: requests, tokens, cost (US dollars), latency, fallbacks, PII redactions")
    public AiSummary aiSummary(@PathVariable String org,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                               @RequestParam(required = false) String environment,
                               @RequestParam(required = false) String aiProxy) {
        return analytics.aiSummary(name(org), from, to, environment, aiProxy);
    }

    @GetMapping("/ai/timeseries")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    public AiTimeseries aiTimeseries(@PathVariable String org,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(required = false) String environment,
                                     @RequestParam(required = false) String aiProxy) {
        return analytics.aiTimeseries(name(org), from, to, environment, aiProxy);
    }

    @GetMapping("/ai/top")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "AI usage by model, model_alias, provider, app, developer, ai_proxy, environment or error",
            description = "Ordered by cost.")
    public AiTop aiTop(@PathVariable String org, @RequestParam(defaultValue = "model") String dimension,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                       @RequestParam(required = false) String environment,
                       @RequestParam(required = false) String aiProxy,
                       @RequestParam(defaultValue = "10") int limit) {
        return analytics.aiTop(name(org), dimension, from, to, environment, aiProxy, limit);
    }

    @GetMapping("/mcp/summary")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "MCP tool calls: count, errors, latency")
    public McpSummary mcpSummary(@PathVariable String org,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) String environment,
                                 @RequestParam(required = false) String mcpServer) {
        return analytics.mcpSummary(name(org), from, to, environment, mcpServer);
    }

    @GetMapping("/mcp/top")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "MCP tool calls by tool, mcp_server, app, developer, environment or server_type")
    public McpTop mcpTop(@PathVariable String org, @RequestParam(defaultValue = "tool") String dimension,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                         @RequestParam(required = false) String environment,
                         @RequestParam(required = false) String mcpServer,
                         @RequestParam(defaultValue = "10") int limit) {
        return analytics.mcpTop(name(org), dimension, from, to, environment, mcpServer, limit);
    }

    @GetMapping("/mcp/calls")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "Most recent MCP tool calls, newest first")
    public List<McpCall> mcpCalls(@PathVariable String org,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(required = false) String environment,
                                  @RequestParam(required = false) String mcpServer,
                                  @RequestParam(defaultValue = "100") int limit) {
        return analytics.mcpCalls(name(org), from, to, environment, mcpServer, limit);
    }

    private String name(String org) {
        return organizations.getAny(org).getName();
    }
}

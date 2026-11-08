package com.portica.management.analytics;

import com.portica.management.analytics.AnalyticsDtos.Events;
import com.portica.management.analytics.AnalyticsDtos.Summary;
import com.portica.management.analytics.AnalyticsDtos.Timeseries;
import com.portica.management.analytics.AnalyticsDtos.Top;
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

/**
 * API traffic analytics for an organization. Every endpoint takes an optional window
 * ({@code from}/{@code to}, ISO-8601; default the last hour, at most 31 days) and optional
 * {@code environment} and {@code apiProxy} filters.
 */
@RestController
@RequestMapping("/api/v1/organizations/{org}/analytics")
@Tag(name = "Analytics", description = "Traffic, latency and errors of API calls")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final OrganizationFinder organizations;

    public AnalyticsController(AnalyticsService analytics, OrganizationFinder organizations) {
        this.analytics = analytics;
        this.organizations = organizations;
    }

    @GetMapping("/summary")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "Request count, error counts and latency percentiles")
    public Summary summary(@PathVariable String org,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                           @RequestParam(required = false) String environment,
                           @RequestParam(required = false) String apiProxy) {
        return analytics.summary(name(org), from, to, environment, apiProxy);
    }

    @GetMapping("/timeseries")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "Requests, errors and latency over time",
            description = "The bucket size follows the window: 1 minute for an hour, up to 6 hours for a month.")
    public Timeseries timeseries(@PathVariable String org,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) String environment,
                                 @RequestParam(required = false) String apiProxy) {
        return analytics.timeseries(name(org), from, to, environment, apiProxy);
    }

    @GetMapping("/top")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "Busiest values of a dimension",
            description = "dimension: api_proxy, environment, app, api_product, developer, status, fault or gateway.")
    public Top top(@PathVariable String org,
                   @RequestParam(defaultValue = "api_proxy") String dimension,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                   @RequestParam(required = false) String environment,
                   @RequestParam(required = false) String apiProxy,
                   @RequestParam(defaultValue = "10") int limit) {
        return analytics.top(name(org), dimension, from, to, environment, apiProxy, limit);
    }

    @GetMapping("/events")
    @PreAuthorize("@authz.hasPermission(#org, 'ANALYTICS_READ')")
    @Operation(summary = "Most recent calls, newest first",
            description = "status: 2xx, 3xx, 4xx, 5xx or errors (4xx and 5xx).")
    public Events events(@PathVariable String org,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                         @RequestParam(required = false) String environment,
                         @RequestParam(required = false) String apiProxy,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "100") int limit) {
        return analytics.events(name(org), from, to, environment, apiProxy, status, limit);
    }

    /** 404 for unknown organizations; analytics of archived ones stay readable. */
    private String name(String org) {
        return organizations.getAny(org).getName();
    }
}

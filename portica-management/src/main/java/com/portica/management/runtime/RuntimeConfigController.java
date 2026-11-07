package com.portica.management.runtime;

import com.portica.management.runtime.RuntimeDtos.RuntimeEnvironmentConfig;
import com.portica.management.runtime.RuntimeDtos.RuntimeIndex;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API polled by gateways (service accounts with the {@code portica-runtime} realm role).
 * Not intended for console or end-user clients.
 */
@RestController
@RequestMapping("/internal/v1/runtime")
@Tag(name = "Runtime (internal)", description = "Configuration feed for Portica gateways")
public class RuntimeConfigController {

    private final RuntimeConfigService runtime;

    public RuntimeConfigController(RuntimeConfigService runtime) {
        this.runtime = runtime;
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
}

package com.portica.management.targetserver;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response payloads for the target servers API. */
public final class TargetServerDtos {

    /** Hostname or IPv4/IPv6 literal; no scheme, port or path. */
    static final String HOST_PATTERN = "^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
            + "|^\\[[0-9A-Fa-f:.]+]$";

    private TargetServerDtos() {
    }

    public record TargetServerRequest(
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "must be a hostname or IP address")
            @Schema(example = "orders.dev.internal")
            String host,

            @NotNull @Min(1) @Max(65535)
            @Schema(example = "8443")
            Integer port,

            @NotNull
            @Schema(example = "HTTPS")
            TargetServerProtocol protocol,

            @Schema(description = "Defaults to true")
            Boolean enabled,

            @Size(max = 1024)
            String description) {
    }

    public record CreateTargetServerRequest(
            @NotBlank @ResourceName @Schema(example = "orders-backend") String name,
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "must be a hostname or IP address")
            String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotNull TargetServerProtocol protocol,
            Boolean enabled,
            @Size(max = 1024) String description) {
    }

    public record TargetServerResponse(UUID id, String name, String host, int port, String protocol,
                                       boolean enabled, String description, String baseUri,
                                       Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static TargetServerResponse from(TargetServer t) {
            return new TargetServerResponse(t.getId(), t.getName(), t.getHost(), t.getPort(), t.getProtocol().name(),
                    t.isEnabled(), t.getDescription(), t.baseUri(), t.getCreatedAt(), t.getCreatedBy(),
                    t.getUpdatedAt(), t.getUpdatedBy());
        }
    }
}

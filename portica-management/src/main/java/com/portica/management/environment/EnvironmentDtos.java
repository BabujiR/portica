package com.portica.management.environment;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response payloads for the environments API. */
public final class EnvironmentDtos {

    private EnvironmentDtos() {
    }

    public record CreateEnvironmentRequest(
            @NotBlank @ResourceName @Schema(example = "staging") String name,
            @Size(max = 128) @Schema(example = "Staging") String displayName,
            @Size(max = 1024) String description) {
    }

    public record UpdateEnvironmentRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) @Schema(description = "Empty string clears the description") String description) {
    }

    public record EnvironmentResponse(UUID id, String name, String displayName, String description,
                                      Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static EnvironmentResponse from(Environment e) {
            return new EnvironmentResponse(e.getId(), e.getName(), e.getDisplayName(), e.getDescription(),
                    e.getCreatedAt(), e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy());
        }
    }
}

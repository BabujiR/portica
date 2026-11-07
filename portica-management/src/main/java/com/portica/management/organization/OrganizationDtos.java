package com.portica.management.organization;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response payloads for the organizations API. */
public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record CreateOrganizationRequest(
            @NotBlank @ResourceName
            @Schema(description = "Immutable identifier used in URLs", example = "acme")
            String name,

            @Size(max = 128)
            @Schema(example = "Acme Corporation")
            String displayName,

            @Size(max = 1024)
            String description,

            @Email @Size(max = 320)
            @Schema(description = "Initial ORG_ADMIN. Invited if they have not signed in yet. "
                    + "Defaults to the caller.", example = "alice@portica.local")
            String adminEmail,

            @Schema(description = "Create the configured default environments (dev, test, prod). Defaults to true.")
            Boolean createDefaultEnvironments) {
    }

    public record UpdateOrganizationRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) @Schema(description = "Empty string clears the description") String description) {
    }

    public record OrganizationResponse(UUID id, String name, String displayName, String description,
                                       String status, Instant createdAt, String createdBy,
                                       Instant updatedAt, String updatedBy) {

        public static OrganizationResponse from(Organization o) {
            return new OrganizationResponse(o.getId(), o.getName(), o.getDisplayName(), o.getDescription(),
                    o.getStatus().name(), o.getCreatedAt(), o.getCreatedBy(), o.getUpdatedAt(), o.getUpdatedBy());
        }
    }
}

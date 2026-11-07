package com.portica.management.membership;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response payloads for the organization members API. */
public final class MembershipDtos {

    private MembershipDtos() {
    }

    public record AddMemberRequest(
            @NotBlank @Email @Size(max = 320)
            @Schema(description = "Invited if this person has not signed in yet", example = "bob@portica.local")
            String email,

            @NotEmpty @Size(max = 10)
            @Schema(example = "[\"VIEWER\"]")
            Set<@NotBlank String> roles) {
    }

    public record UpdateMemberRolesRequest(
            @NotEmpty @Size(max = 10)
            @Schema(description = "Replaces the member's roles", example = "[\"API_ADMIN\"]")
            Set<@NotBlank String> roles) {
    }

    public record MemberResponse(UUID userId, String email, String displayName, String status,
                                 List<String> roles, Instant lastLoginAt) {
    }
}

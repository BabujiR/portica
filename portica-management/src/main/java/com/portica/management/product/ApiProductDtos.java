package com.portica.management.product;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiProductDtos {

    /** RFC 6749 scope-token characters. */
    static final String SCOPE = "^[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,128}$";
    /** A path pattern inside a proxy, e.g. {@code /pets/**}. */
    static final String PATH = "^/[^\\s]{0,255}$";

    private ApiProductDtos() {
    }

    public record CreateApiProductRequest(
            @NotBlank @ResourceName @Schema(example = "pets-gold") String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Schema(description = "AUTO (default) approves apps immediately; MANUAL leaves them pending") ApprovalType approvalType,
            @Schema(description = "Portal visibility (Phase 8); default PUBLIC") ProductAccess access,
            @Size(max = 50) @Schema(description = "Environments where the product is offered; empty means all")
            List<@ResourceName String> environments,
            @Size(max = 200) @Schema(description = "API proxies the product grants access to", example = "[\"pets\"]")
            List<@ResourceName String> proxies,
            @Size(max = 200) @Schema(description = "Path patterns within those proxies (* one segment, ** any); empty means all",
                    example = "[\"/pets/**\"]")
            List<@Pattern(regexp = PATH) String> paths,
            @Size(max = 100) @Schema(description = "OAuth scopes apps can request with this product", example = "[\"pets.read\"]")
            List<@Pattern(regexp = SCOPE) String> scopes,
            @Valid ProductQuota quota,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes) {
    }

    /** Full replacement of a product's definition. */
    public record UpdateApiProductRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            ApprovalType approvalType,
            ProductAccess access,
            @Size(max = 50) List<@ResourceName String> environments,
            @Size(max = 200) List<@ResourceName String> proxies,
            @Size(max = 200) List<@Pattern(regexp = PATH) String> paths,
            @Size(max = 100) List<@Pattern(regexp = SCOPE) String> scopes,
            @Valid ProductQuota quota,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes) {
    }

    public record ApiProductResponse(UUID id, String name, String displayName, String description,
                                     ApprovalType approvalType, ProductAccess access, List<String> environments,
                                     List<String> proxies, List<String> paths, List<String> scopes,
                                     ProductQuota quota, Map<String, String> attributes,
                                     Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static ApiProductResponse from(ApiProduct p) {
            return new ApiProductResponse(p.getId(), p.getName(), p.getDisplayName(), p.getDescription(),
                    p.getApprovalType(), p.getAccess(), p.environments(), p.proxies(), p.paths(), p.scopes(),
                    p.quota(), p.attributes(), p.getCreatedAt(), p.getCreatedBy(), p.getUpdatedAt(), p.getUpdatedBy());
        }
    }
}

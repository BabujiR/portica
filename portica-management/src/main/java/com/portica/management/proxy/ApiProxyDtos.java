package com.portica.management.proxy;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response payloads for API proxies and revisions. */
public final class ApiProxyDtos {

    /** One or more path segments, no trailing slash. Example: {@code /orders/v1}. */
    public static final String BASE_PATH_PATTERN = "^(/[A-Za-z0-9._~-]+)+$";

    private ApiProxyDtos() {
    }

    // ----------------------------------------------------------------- requests

    public record TargetRequest(
            @Size(max = 2048)
            @Schema(description = "Direct backend URL. Use this OR server.", example = "https://petstore3.swagger.io/api/v3")
            String url,

            @Size(max = 63)
            @Schema(description = "Name of a target server defined in each environment. Use this OR url.",
                    example = "petstore-backend")
            String server,

            @Size(max = 1024)
            @Schema(description = "Path prefix on the target server (server targets only)", example = "/api/v3")
            String path) {
    }

    public record CreateRevisionRequest(
            @NotBlank @Size(max = 255) @Pattern(regexp = BASE_PATH_PATTERN,
                    message = "must look like /segment or /segment/segment (letters, digits, . _ ~ -), no trailing slash")
            @Schema(description = "Path clients call, below /{org}/{env} on the gateway", example = "/petstore")
            String basePath,

            @NotNull @Valid
            TargetRequest target,

            @Min(100) @Max(60_000)
            @Schema(description = "Defaults to 5000")
            Integer connectTimeoutMs,

            @Min(100) @Max(300_000)
            @Schema(description = "Defaults to 30000")
            Integer readTimeoutMs,

            @Size(max = 1024)
            @Schema(description = "What changed in this revision", example = "Initial version")
            String description,

            @Schema(description = "OpenAPI 3.x or Swagger 2.0 document (YAML or JSON) describing the API")
            String openApiSpec) {
    }

    public record CreateApiProxyRequest(
            @NotBlank @ResourceName @Schema(example = "petstore") String name,
            @Size(max = 128) @Schema(example = "Petstore API") String displayName,
            @Size(max = 1024) String description,
            @NotNull @Valid CreateRevisionRequest revision) {
    }

    public record UpdateApiProxyRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) @Schema(description = "Empty string clears the description") String description) {
    }

    public record ImportOpenApiRequest(
            @NotBlank @ResourceName @Schema(example = "petstore")
            String name,

            @Size(max = 128)
            @Schema(description = "Defaults to info.title from the spec")
            String displayName,

            @Size(max = 255) @Pattern(regexp = BASE_PATH_PATTERN, message = "must look like /segment or /segment/segment")
            @Schema(description = "Defaults to /{name}")
            String basePath,

            @Valid
            @Schema(description = "Defaults to the spec's first absolute server URL")
            TargetRequest target,

            @NotBlank
            @Schema(description = "The OpenAPI 3.x or Swagger 2.0 document as a string (YAML or JSON, max 2 MB)")
            String spec) {
    }

    // ----------------------------------------------------------------- responses

    public record TargetResponse(String url, String server, String path) {

        static TargetResponse from(TargetSpec t) {
            return new TargetResponse(t.url(), t.server(), t.path().isEmpty() ? "/" : t.path());
        }
    }

    public record ApiProxyResponse(UUID id, String name, String displayName, String description, int latestRevision,
                                   Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static ApiProxyResponse from(ApiProxy p) {
            return new ApiProxyResponse(p.getId(), p.getName(), p.getDisplayName(), p.getDescription(),
                    p.getLatestRevision(), p.getCreatedAt(), p.getCreatedBy(), p.getUpdatedAt(), p.getUpdatedBy());
        }
    }

    public record RevisionResponse(String apiProxy, int revision, String description, String basePath,
                                   TargetResponse target, int connectTimeoutMs, int readTimeoutMs,
                                   boolean hasOpenApiSpec, Instant createdAt, String createdBy) {

        public static RevisionResponse from(ApiProxyRevision r) {
            return new RevisionResponse(r.getApiProxy().getName(), r.getRevision(), r.getDescription(),
                    r.getBasePath(), TargetResponse.from(r.target()), r.getConnectTimeoutMs(), r.getReadTimeoutMs(),
                    r.getOpenApiSpec() != null, r.getCreatedAt(), r.getCreatedBy());
        }
    }

    public record CreateApiProxyResponse(ApiProxyResponse apiProxy, RevisionResponse revision) {
    }

    public record ImportResult(ApiProxyResponse apiProxy, RevisionResponse revision, boolean createdApiProxy,
                               String specVersion, String apiVersion, int operationCount) {
    }

    public record OperationResponse(String method, String path, String operationId, String summary) {
    }

    public record OperationsResponse(String specVersion, String title, String apiVersion,
                                     List<OperationResponse> operations) {
    }
}

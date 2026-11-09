package com.portica.management.portal;

import com.portica.common.validation.ResourceName;
import com.portica.management.analytics.AnalyticsDtos.Summary;
import com.portica.management.analytics.AnalyticsDtos.Timeseries;
import com.portica.management.analytics.AnalyticsDtos.Top;
import com.portica.management.mcp.McpDtos.ToolView;
import com.portica.management.product.ApprovalType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Payloads of the internal developer portal API (called by portica-portal on behalf of a developer). */
public final class PortalDtos {

    private PortalDtos() {
    }

    /** What a developer can discover: public products, the APIs and MCP servers they grant, per environment. */
    public record Catalog(String organization, List<CatalogProduct> products, List<CatalogApi> apis,
                          List<CatalogMcpServer> mcpServers) {
    }

    public record CatalogProduct(String name, String displayName, String description, ApprovalType approvalType,
                                 List<String> environments, List<String> apis, List<String> scopes, String quota) {
    }

    /** {@code deployments}: where the API is live and at which public path below the gateway. */
    public record CatalogApi(String name, String displayName, String description, List<String> products,
                             List<ApiDeployment> deployments, boolean documented) {
    }

    public record ApiDeployment(String environment, int revision, String basePath, String url) {
    }

    public record CatalogMcpServer(String name, String displayName, String description, String type,
                                   List<String> products, List<McpEndpoint> endpoints) {
    }

    public record McpEndpoint(String environment, String url) {
    }

    /** One API's documentation in an environment: the operations and the OpenAPI document of the live revision. */
    public record ApiDocs(String name, String displayName, String description, String environment, int revision,
                          String url, String title, String version, List<DocOperation> operations, String openApiSpec) {
    }

    public record DocOperation(String method, String path, String operationId, String summary) {
    }

    public record McpTools(String server, String environment, List<ToolView> tools) {
    }

    /** Self-registration: the e-mail address comes from the developer's verified identity, not the body. */
    public record RegisterRequest(@NotBlank @Size(max = 128) String firstName, @NotBlank @Size(max = 128) String lastName) {
    }

    public record PortalAppRequest(
            @NotBlank @ResourceName String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Pattern(regexp = "^https?://\\S+$") String callbackUrl,
            @Size(min = 1, max = 20) List<@ResourceName String> apiProducts) {
    }

    public record PortalKeyRequest(@Size(max = 20) List<@ResourceName String> apiProducts,
                                   @Min(3600) @Max(63_072_000) Long expiresInSeconds) {
    }

    public record Usage(Summary summary, Timeseries timeseries, Top byApp, Top byApi) {
    }
}

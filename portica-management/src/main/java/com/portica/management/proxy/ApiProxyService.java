package com.portica.management.proxy;

import com.portica.common.http.PorticaPaths;
import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.policy.BundleValidator;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.proxy.ApiProxyDtos.CreateApiProxyRequest;
import com.portica.management.proxy.ApiProxyDtos.CreateRevisionRequest;
import com.portica.management.proxy.ApiProxyDtos.ImportOpenApiRequest;
import com.portica.management.proxy.ApiProxyDtos.TargetRequest;
import com.portica.management.proxy.ApiProxyDtos.UpdateApiProxyRequest;
import com.portica.management.proxy.openapi.OpenApiDocument;
import com.portica.management.proxy.openapi.OpenApiParser;
import com.portica.management.shared.GatewayNames;
import com.portica.management.shared.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API proxies and their immutable revisions, including creation from an OpenAPI document.
 */
@Service
public class ApiProxyService {

    static final String RESOURCE_TYPE = "ApiProxy";
    static final String REVISION_RESOURCE_TYPE = "ApiProxyRevision";

    static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private final ApiProxyRepository proxies;
    private final ApiProxyRevisionRepository revisions;
    private final DeploymentRepository deployments;
    private final OrganizationFinder organizations;
    private final OpenApiParser openApiParser;
    private final AuditService audit;
    private final GatewayNames gatewayNames;

    public ApiProxyService(ApiProxyRepository proxies, ApiProxyRevisionRepository revisions,
                           DeploymentRepository deployments, OrganizationFinder organizations,
                           OpenApiParser openApiParser, AuditService audit, GatewayNames gatewayNames) {
        this.gatewayNames = gatewayNames;
        this.proxies = proxies;
        this.revisions = revisions;
        this.deployments = deployments;
        this.organizations = organizations;
        this.openApiParser = openApiParser;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- proxies

    @Transactional(readOnly = true)
    public Page<ApiProxy> list(String org, String q, Pageable pageable) {
        return proxies.search(organizations.getActive(org).getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public ApiProxy get(String org, String name) {
        return find(organizations.getActive(org), name);
    }

    /** Creates the proxy and its revision 1 atomically. */
    @Transactional
    public ApiProxyRevision create(String org, CreateApiProxyRequest request) {
        Organization organization = organizations.getActive(org);
        if (proxies.existsByOrganization_IdAndName(organization.getId(), request.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, request.name());
        }
        gatewayNames.requireFreeForApiProxy(organization.getId(), request.name());
        ApiProxy proxy = proxies.save(new ApiProxy(organization, request.name(), request.displayName(),
                request.description()));
        ApiProxyRevision revision = addRevision(proxy, request.revision());
        audit.record(organization.getId(), "api-proxy.created", RESOURCE_TYPE, proxy.getName(),
                revisionDetails(revision));
        return revision;
    }

    @Transactional
    public ApiProxy update(String org, String name, UpdateApiProxyRequest request) {
        Organization organization = organizations.getActive(org);
        ApiProxy proxy = find(organization, name);
        proxy.update(request.displayName(), request.description());
        audit.record(organization.getId(), "api-proxy.updated", RESOURCE_TYPE, name, Map.of());
        return proxy;
    }

    @Transactional
    public void delete(String org, String name) {
        Organization organization = organizations.getActive(org);
        ApiProxy proxy = find(organization, name);
        if (deployments.existsByApiProxy_Id(proxy.getId())) {
            throw new BusinessRuleViolationException(
                    "API proxy '%s' is deployed; undeploy it from every environment first".formatted(name));
        }
        proxies.delete(proxy); // revisions are removed by ON DELETE CASCADE
        audit.record(organization.getId(), "api-proxy.deleted", RESOURCE_TYPE, name, Map.of());
    }

    // ----------------------------------------------------------------- revisions

    @Transactional(readOnly = true)
    public Page<ApiProxyRevision> listRevisions(String org, String name, Pageable pageable) {
        ApiProxy proxy = find(organizations.getActive(org), name);
        return revisions.findByApiProxy_IdOrderByRevisionDesc(proxy.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public ApiProxyRevision getRevision(String org, String name, int revision) {
        return findRevision(find(organizations.getActive(org), name), revision);
    }

    @Transactional
    public ApiProxyRevision createRevision(String org, String name, CreateRevisionRequest request) {
        Organization organization = organizations.getActive(org);
        ApiProxy proxy = find(organization, name);
        ApiProxyRevision revision = addRevision(proxy, request);
        audit.record(organization.getId(), "api-proxy.revision.created", REVISION_RESOURCE_TYPE,
                name + "/" + revision.getRevision(), revisionDetails(revision));
        return revision;
    }

    @Transactional
    public void deleteRevision(String org, String name, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        ApiProxyRevision revision = findRevision(find(organization, name), revisionNumber);
        if (deployments.existsByRevision_Id(revision.getId())) {
            throw new BusinessRuleViolationException(
                    "Revision %d of '%s' is deployed; undeploy it first".formatted(revisionNumber, name));
        }
        revisions.delete(revision);
        audit.record(organization.getId(), "api-proxy.revision.deleted", REVISION_RESOURCE_TYPE,
                name + "/" + revisionNumber, Map.of());
    }

    @Transactional(readOnly = true)
    public Optional<OpenApiDocument> describeRevision(String org, String name, int revisionNumber) {
        ApiProxyRevision revision = getRevision(org, name, revisionNumber);
        return Optional.ofNullable(revision.getOpenApiSpec()).map(openApiParser::parse);
    }

    // ----------------------------------------------------------------- OpenAPI import

    /**
     * Creates a proxy from an OpenAPI document, or adds a new revision if the proxy already exists
     * (re-importing an updated spec). Defaults come from the spec: display name from
     * {@code info.title}, target from the first absolute server URL, base path {@code /{name}}.
     */
    @Transactional
    public ImportOutcome importOpenApi(String org, ImportOpenApiRequest request) {
        Organization organization = organizations.getActive(org);
        OpenApiDocument document = openApiParser.parse(request.spec());

        TargetRequest target = request.target();
        if (target == null || (isBlank(target.url()) && isBlank(target.server()))) {
            if (document.serverUrl() == null) {
                throw new InvalidRequestException("The spec has no absolute http(s) server URL; "
                        + "provide 'target.url' or 'target.server'");
            }
            target = new TargetRequest(document.serverUrl(), null, null);
        }
        String basePath = isBlank(request.basePath()) ? "/" + request.name() : request.basePath();
        String revisionNote = "Imported from OpenAPI '%s'%s".formatted(document.title(),
                document.apiVersion() == null ? "" : " version " + document.apiVersion());
        CreateRevisionRequest revisionRequest = new CreateRevisionRequest(basePath, target, null, null,
                truncate(revisionNote, 1024), request.spec(), null, null);

        Optional<ApiProxy> existing = proxies.findByOrganization_IdAndName(organization.getId(), request.name());
        if (existing.isEmpty()) {
            gatewayNames.requireFreeForApiProxy(organization.getId(), request.name());
        }
        ApiProxy proxy = existing.orElseGet(() -> proxies.save(new ApiProxy(organization, request.name(),
                isBlank(request.displayName()) ? truncate(document.title(), 128) : request.displayName(),
                truncate(document.description(), 1024))));
        ApiProxyRevision revision = addRevision(proxy, revisionRequest);

        Map<String, Object> details = revisionDetails(revision);
        details.put("openApiVersion", document.specVersion());
        details.put("operations", document.operations().size());
        audit.record(organization.getId(), "api-proxy.imported", RESOURCE_TYPE, proxy.getName(), details);
        return new ImportOutcome(revision, existing.isEmpty(), document);
    }

    public record ImportOutcome(ApiProxyRevision revision, boolean createdApiProxy, OpenApiDocument document) {
    }

    // ----------------------------------------------------------------- helpers

    private ApiProxyRevision addRevision(ApiProxy proxy, CreateRevisionRequest request) {
        if (PorticaPaths.isReserved(request.basePath())) {
            throw new InvalidRequestException("Base path '%s' is reserved for Portica's built-in gateway endpoints (/oauth2, /ai, /mcp)"
                    .formatted(request.basePath()));
        }
        TargetSpec target = TargetSpec.of(request.target().url(), request.target().server(), request.target().path());
        if (request.openApiSpec() != null && !request.openApiSpec().isBlank()) {
            openApiParser.parse(request.openApiSpec()); // reject invalid specs up front
        }
        List<PolicyDefinition> policies = request.policies() == null ? List.of() : request.policies();
        ProxyFlows flows = request.flows() == null ? ProxyFlows.EMPTY : request.flows();
        List<String> problems = BundleValidator.validateProxy(policies, flows);
        if (!problems.isEmpty()) {
            throw new InvalidBundleException(problems);
        }
        int number = proxy.nextRevisionNumber();
        return revisions.save(new ApiProxyRevision(proxy, number, request.description(), request.basePath(), target,
                request.connectTimeoutMs() == null ? DEFAULT_CONNECT_TIMEOUT_MS : request.connectTimeoutMs(),
                request.readTimeoutMs() == null ? DEFAULT_READ_TIMEOUT_MS : request.readTimeoutMs(),
                isBlank(request.openApiSpec()) ? null : request.openApiSpec(), policies, flows));
    }

    private ApiProxy find(Organization organization, String name) {
        return proxies.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private ApiProxyRevision findRevision(ApiProxy proxy, int revision) {
        return revisions.findByApiProxy_IdAndRevision(proxy.getId(), revision)
                .orElseThrow(() -> new ResourceNotFoundException(REVISION_RESOURCE_TYPE,
                        proxy.getName() + "/" + revision));
    }

    private static Map<String, Object> revisionDetails(ApiProxyRevision revision) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("revision", revision.getRevision());
        details.put("basePath", revision.getBasePath());
        details.put("target", revision.target().describe());
        details.put("policies", revision.policies().size());
        return details;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

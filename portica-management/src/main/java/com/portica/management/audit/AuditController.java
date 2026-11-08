package com.portica.management.audit;

import com.portica.common.api.PageResponse;
import com.portica.common.error.InvalidRequestException;
import com.portica.management.shared.SearchTerms;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{org}/audit-events")
@Tag(name = "Audit", description = "Who changed what, and when")
public class AuditController {

    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };
    // bounds for open-ended searches; both are valid Postgres timestamptz values
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2200-01-01T00:00:00Z");

    private final AuditEventRepository events;
    private final OrganizationFinder organizations;
    private final JsonMapper jsonMapper;

    public AuditController(AuditEventRepository events, OrganizationFinder organizations, JsonMapper jsonMapper) {
        this.events = events;
        this.organizations = organizations;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("@authz.hasPermission(#org, 'AUDIT_READ')")
    @Operation(summary = "List audit events, newest first",
            description = "Optional filters: q (action, resource, actor or request id contains), resourceType, "
                    + "from and to (ISO-8601 instants).")
    public PageResponse<AuditEventResponse> list(@PathVariable String org,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String resourceType,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                 @PageableDefault(size = 50) Pageable pageable) {
        UUID organizationId = organizations.getAny(org).getId();
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidRequestException("from must be before to");
        }
        // Ordering is fixed (newest first); client-supplied sort is ignored for this endpoint.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        String term = SearchTerms.normalize(q);
        String type = resourceType == null ? "" : resourceType.strip();
        if (term.isEmpty() && type.isEmpty() && from == null && to == null) {
            return PageResponses.from(events.findByOrganizationIdOrderByOccurredAtDesc(organizationId, unsorted),
                    this::toResponse);
        }
        return PageResponses.from(events.search(organizationId, type, from == null ? EARLIEST : from,
                to == null ? LATEST : to, term, unsorted), this::toResponse);
    }

    @GetMapping("/resource-types")
    @Transactional(readOnly = true)
    @PreAuthorize("@authz.hasPermission(#org, 'AUDIT_READ')")
    @Operation(summary = "Resource types that appear in this organization's audit log")
    public List<String> resourceTypes(@PathVariable String org) {
        return events.resourceTypes(organizations.getAny(org).getId());
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getAction(), e.getResourceType(), e.getResourceName(),
                e.getActorEmail() != null ? e.getActorEmail() : e.getActorSubject(),
                jsonMapper.readValue(e.getDetails(), DETAILS), e.getRequestId(), e.getOccurredAt());
    }

    public record AuditEventResponse(UUID id, String action, String resourceType, String resourceName,
                                     String actor, Map<String, Object> details, String requestId,
                                     Instant occurredAt) {
    }
}

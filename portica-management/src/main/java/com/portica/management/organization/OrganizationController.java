package com.portica.management.organization;

import com.portica.common.api.PageResponse;
import com.portica.management.organization.OrganizationDtos.CreateOrganizationRequest;
import com.portica.management.organization.OrganizationDtos.OrganizationResponse;
import com.portica.management.organization.OrganizationDtos.UpdateOrganizationRequest;
import com.portica.management.shared.PageRequests;
import com.portica.management.shared.PageResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations", description = "Tenants. Creating and archiving requires the platform admin role.")
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    @Operation(summary = "List organizations visible to the caller")
    public PageResponse<OrganizationResponse> list(@RequestParam(required = false) String q,
                                                   @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                                   Pageable pageable) {
        Pageable checked = PageRequests.requireSortable(pageable, PageRequests.STANDARD_SORTS);
        return PageResponses.from(organizations.list(q, checked), OrganizationResponse::from);
    }

    @GetMapping("/{org}")
    @PreAuthorize("@authz.hasPermission(#org, 'ORG_READ')")
    @Operation(summary = "Get an organization")
    public OrganizationResponse get(@PathVariable String org) {
        return OrganizationResponse.from(organizations.get(org));
    }

    @PostMapping
    @PreAuthorize("@authz.isPlatformAdmin()")
    @Operation(summary = "Create an organization",
            description = "Also creates the default environments and grants ORG_ADMIN to adminEmail (or the caller).")
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        Organization created = organizations.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{org}").buildAndExpand(created.getName()).toUri();
        return ResponseEntity.created(location).body(OrganizationResponse.from(created));
    }

    @PatchMapping("/{org}")
    @PreAuthorize("@authz.hasPermission(#org, 'ORG_UPDATE')")
    @Operation(summary = "Update an organization's display name or description")
    public OrganizationResponse update(@PathVariable String org, @Valid @RequestBody UpdateOrganizationRequest request) {
        return OrganizationResponse.from(organizations.update(org, request));
    }

    @DeleteMapping("/{org}")
    @PreAuthorize("@authz.isPlatformAdmin()")
    @Operation(summary = "Archive an organization", description = "Soft delete; members immediately lose access.")
    public ResponseEntity<Void> archive(@PathVariable String org) {
        organizations.archive(org);
        return ResponseEntity.noContent().build();
    }
}

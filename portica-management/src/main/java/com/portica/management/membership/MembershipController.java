package com.portica.management.membership;

import com.portica.common.api.PageResponse;
import com.portica.management.membership.MembershipDtos.AddMemberRequest;
import com.portica.management.membership.MembershipDtos.MemberResponse;
import com.portica.management.membership.MembershipDtos.UpdateMemberRolesRequest;
import com.portica.management.shared.PageRequests;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{org}/members")
@Tag(name = "Members", description = "Who can access an organization, and with which roles")
public class MembershipController {

    private static final Set<String> MEMBER_SORTS = Set.of("displayName", "email", "createdAt", "lastLoginAt");

    private final MembershipService memberships;

    public MembershipController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(#org, 'MEMBER_READ')")
    @Operation(summary = "List members", description = "Search matches email or display name.")
    public PageResponse<MemberResponse> list(@PathVariable String org,
                                             @RequestParam(required = false) String q,
                                             @PageableDefault(size = 20, sort = "displayName", direction = Sort.Direction.ASC)
                                             Pageable pageable) {
        return memberships.list(org, q, PageRequests.requireSortable(pageable, MEMBER_SORTS));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(#org, 'MEMBER_READ')")
    @Operation(summary = "Get a member")
    public MemberResponse get(@PathVariable String org, @PathVariable UUID userId) {
        return memberships.get(org, userId);
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(#org, 'MEMBER_MANAGE')")
    @Operation(summary = "Add a member", description = "Invites the user by email if they have not signed in yet.")
    public ResponseEntity<MemberResponse> add(@PathVariable String org, @Valid @RequestBody AddMemberRequest request) {
        MemberResponse member = memberships.add(org, request.email(), request.roles());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{userId}").buildAndExpand(member.userId()).toUri();
        return ResponseEntity.created(location).body(member);
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("@authz.hasPermission(#org, 'MEMBER_MANAGE')")
    @Operation(summary = "Replace a member's roles")
    public MemberResponse replaceRoles(@PathVariable String org, @PathVariable UUID userId,
                                       @Valid @RequestBody UpdateMemberRolesRequest request) {
        return memberships.replaceRoles(org, userId, request.roles());
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@authz.hasPermission(#org, 'MEMBER_MANAGE')")
    @Operation(summary = "Remove a member from the organization")
    public ResponseEntity<Void> remove(@PathVariable String org, @PathVariable UUID userId) {
        memberships.remove(org, userId);
        return ResponseEntity.noContent().build();
    }
}

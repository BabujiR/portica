package com.portica.management.rbac;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Built-in roles and the permissions they grant")
public class RoleController {

    private final RoleRepository roles;

    public RoleController(RoleRepository roles) {
        this.roles = roles;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List roles", description = "Available to every authenticated user so UIs can render role pickers.")
    public List<RoleResponse> list() {
        return roles.findAllByOrderByCodeAsc().stream().map(RoleResponse::from).toList();
    }

    public record RoleResponse(String code, String displayName, String description, boolean builtIn,
                               List<String> permissions) {

        static RoleResponse from(Role role) {
            List<String> permissions = role.getPermissions().stream().map(Enum::name).sorted().toList();
            return new RoleResponse(role.getCode(), role.getDisplayName(), role.getDescription(),
                    role.isBuiltIn(), permissions);
        }
    }
}

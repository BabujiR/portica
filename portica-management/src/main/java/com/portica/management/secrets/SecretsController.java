package com.portica.management.secrets;

import com.portica.management.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide secret key management: status, re-wrap after a key change, and Vault key rotation. */
@RestController
@RequestMapping("/api/v1/platform/secrets")
@Tag(name = "Platform secrets", description = "Key-encryption keys protecting stored secrets (platform admins)")
public class SecretsController {

    private final SecretRotationService rotation;
    private final AuditService audit;

    public SecretsController(SecretRotationService rotation, AuditService audit) {
        this.rotation = rotation;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("@authz.isPlatformAdmin()")
    @Operation(summary = "Which key-encryption keys protect stored secrets")
    public SecretRotationService.Status status() {
        return rotation.status();
    }

    @PostMapping("/rewrap")
    @PreAuthorize("@authz.isPlatformAdmin()")
    @Operation(summary = "Re-wrap every data key with the current key-encryption key",
            description = "With rotateKey=true, first creates a new key version in Vault (Transit rotate).")
    public SecretRotationService.RewrapResult rewrap(@RequestParam(defaultValue = "false") boolean rotateKey) {
        SecretRotationService.RewrapResult result = rotateKey ? rotation.rotateKey() : rotation.rewrapAll();
        audit.recordPlatform(rotateKey ? "secrets.key.rotated" : "secrets.rewrapped", result.backend(),
                java.util.Map.of("checked", result.checked(), "rewrapped", result.rewrapped(), "failed", result.failed()));
        return result;
    }
}

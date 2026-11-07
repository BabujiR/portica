package com.portica.management.kvm;

import com.portica.common.policy.config.KeyValueMapOperationsConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class KeyValueMapDtos {

    /** Entry keys: safe in URL paths. */
    public static final String ENTRY_KEY = "^[A-Za-z0-9._:\\-]{1,255}$";
    public static final String MASK = "*****";

    private KeyValueMapDtos() {
    }

    public record CreateKeyValueMapRequest(
            @NotBlank @Pattern(regexp = KeyValueMapOperationsConfig.KEY_VALUE_MAP_NAME) @Schema(example = "backend-config")
            String name,
            @Schema(description = "Encrypt values at rest and never return them from the API (default false)")
            Boolean encrypted) {
    }

    public record KeyValueMapResponse(UUID id, String name, String scope, String environment, boolean encrypted,
                                      long entryCount, Instant createdAt, String createdBy) {
    }

    public record PutEntryRequest(@NotNull @Size(max = 65_536) String value) {
    }

    /** {@code value} is {@value #MASK} for encrypted maps. */
    public record EntryResponse(String key, String value, Instant updatedAt, String updatedBy) {
    }
}

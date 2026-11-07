package com.portica.management.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Requests an app may make per window through the product (enforced by Quota with useProductQuota). */
public record ProductQuota(
        @NotNull @Min(1) @Max(1_000_000_000_000L) @Schema(example = "1000") Long count,
        @Min(1) @Max(10_000) @Schema(example = "1") Integer interval,
        @Pattern(regexp = "second|minute|hour|day|week") @Schema(example = "hour") String timeUnit) {

    public ProductQuota {
        interval = interval == null ? 1 : interval;
        timeUnit = timeUnit == null ? "minute" : timeUnit;
    }
}

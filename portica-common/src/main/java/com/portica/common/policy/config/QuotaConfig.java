package com.portica.common.policy.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quota: allows {@code count} requests per {@code interval timeUnit}, counted across all gateway
 * instances (Redis). Windows are fixed and aligned to the epoch (UTC).
 *
 * @param count       allowed requests per window (required)
 * @param interval    window length in {@code timeUnit}s (default 1)
 * @param timeUnit    second, minute (default), hour, day or week
 * @param identifier  template giving the counter key, e.g. {@code {verifyapikey.verify-key.client_id}};
 *                    empty (default) means one shared counter for the API proxy
 * @param weight      template giving how much each request counts (default 1)
 * @param failOpen    allow traffic when the counter store is unavailable (default true)
 * @param emitHeaders add RateLimit-Limit/Remaining/Reset response headers (default true)
 */
public record QuotaConfig(Long count, Integer interval, String timeUnit, String identifier, String weight,
                          Boolean failOpen, Boolean emitHeaders) implements PolicyConfig {

    private static final Set<String> UNITS = Set.of("second", "minute", "hour", "day", "week");

    public QuotaConfig {
        interval = interval == null ? 1 : interval;
        timeUnit = timeUnit == null ? "minute" : timeUnit.toLowerCase(Locale.ROOT);
        identifier = identifier == null ? "" : identifier;
        weight = weight == null || weight.isBlank() ? "1" : weight;
        failOpen = failOpen == null || failOpen;
        emitHeaders = emitHeaders == null || emitHeaders;
    }

    public Duration window() {
        Duration unit = switch (timeUnit) {
            case "second" -> Duration.ofSeconds(1);
            case "hour" -> Duration.ofHours(1);
            case "day" -> Duration.ofDays(1);
            case "week" -> Duration.ofDays(7);
            default -> Duration.ofMinutes(1);
        };
        return unit.multipliedBy(interval);
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .require(count != null, "'count' is required")
                .range(count, 1, 1_000_000_000_000L, "count")
                .range(interval, 1, 10_000, "interval")
                .oneOf(timeUnit, UNITS, "timeUnit")
                .list();
    }
}

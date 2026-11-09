package com.portica.aigateway.quota;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Fixed quota windows in UTC: {@code interval} units of minute, hour, day, week (from Monday) or month.
 *
 * @param id     identifies the window in counter keys
 * @param resets when the next window starts
 */
public record QuotaWindow(long id, Instant resets) {

    public static QuotaWindow of(String timeUnit, int interval, Instant now) {
        int n = Math.max(1, interval);
        if ("month".equals(timeUnit)) {
            YearMonth ym = YearMonth.from(now.atZone(ZoneOffset.UTC));
            long index = ym.getYear() * 12L + ym.getMonthValue() - 1;
            long window = Math.floorDiv(index, n);
            long nextIndex = (window + 1) * n;
            Instant resets = YearMonth.of((int) (nextIndex / 12), (int) (nextIndex % 12) + 1)
                    .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new QuotaWindow(window, resets);
        }
        long unit = switch (timeUnit == null ? "day" : timeUnit) {
            case "minute" -> 60;
            case "hour" -> 3_600;
            case "week" -> 7 * 86_400;
            default -> 86_400;
        };
        long length = unit * n;
        // weeks start on Monday: the epoch (1970-01-01) was a Thursday
        long offset = "week".equals(timeUnit) ? 3 * 86_400 : 0;
        long window = Math.floorDiv(now.getEpochSecond() + offset, length);
        return new QuotaWindow(window, Instant.ofEpochSecond((window + 1) * length - offset));
    }

    /** How long counters for this window must live. */
    public Duration ttl(Instant now) {
        return Duration.between(now, resets).plusMinutes(5);
    }
}

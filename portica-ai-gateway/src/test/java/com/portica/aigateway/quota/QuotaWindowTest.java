package com.portica.aigateway.quota;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaWindowTest {

    @Test
    void windowsResetOnUnitBoundariesInUtc() {
        Instant now = Instant.parse("2026-11-07T10:15:30Z");   // a Saturday
        assertThat(QuotaWindow.of("minute", 1, now).resets()).isEqualTo(Instant.parse("2026-11-07T10:16:00Z"));
        assertThat(QuotaWindow.of("hour", 6, now).resets()).isEqualTo(Instant.parse("2026-11-07T12:00:00Z"));
        assertThat(QuotaWindow.of("day", 1, now).resets()).isEqualTo(Instant.parse("2026-11-08T00:00:00Z"));
        assertThat(QuotaWindow.of("week", 1, now).resets()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
        assertThat(QuotaWindow.of("month", 1, now).resets()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(QuotaWindow.of("month", 3, Instant.parse("2026-12-31T23:00:00Z")).resets())
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void sameWindowSameId() {
        assertThat(QuotaWindow.of("day", 1, Instant.parse("2026-11-07T00:00:01Z")).id())
                .isEqualTo(QuotaWindow.of("day", 1, Instant.parse("2026-11-07T23:59:59Z")).id());
    }
}

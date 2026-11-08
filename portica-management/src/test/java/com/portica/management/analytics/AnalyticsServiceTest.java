package com.portica.management.analytics;

import com.portica.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsServiceTest {

    @Test
    void windowDefaultsToTheLastHourAndIsCapped() {
        AnalyticsDtos.Window w = AnalyticsService.window(null, null, " ", null);
        assertThat(Duration.between(w.from(), w.to())).isEqualTo(Duration.ofHours(1));
        assertThat(w.environment()).isNull();
        Instant now = Instant.now();
        assertThatThrownBy(() -> AnalyticsService.window(now.minus(Duration.ofDays(40)), now, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AnalyticsService.window(now, now, null, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void bucketsKeepChartsReadable() {
        assertThat(AnalyticsService.bucketSeconds(Duration.ofMinutes(15))).isEqualTo(60);
        assertThat(AnalyticsService.bucketSeconds(Duration.ofHours(24))).isEqualTo(900);
        assertThat(AnalyticsService.bucketSeconds(Duration.ofDays(7))).isEqualTo(3600);
        assertThat(Duration.ofDays(31).toSeconds() / AnalyticsService.bucketSeconds(Duration.ofDays(31))).isLessThanOrEqualTo(200);
    }

    @Test
    void statusClassesAndDimensionsAreWhitelisted() {
        assertThat(AnalyticsService.statusRange("5xx")).containsExactly(500, 599);
        assertThat(AnalyticsService.statusRange(null)).containsExactly(0, 999);
        assertThatThrownBy(() -> AnalyticsService.statusRange("1=1")).isInstanceOf(InvalidRequestException.class);
        assertThat(AnalyticsService.DIMENSIONS).doesNotContainKey("client_ip");
    }
}

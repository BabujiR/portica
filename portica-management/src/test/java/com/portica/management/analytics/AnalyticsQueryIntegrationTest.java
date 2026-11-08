package com.portica.management.analytics;

import com.portica.common.analytics.AnalyticsSchema;
import com.portica.common.analytics.ClickHouseClient;
import com.portica.management.analytics.AnalyticsDtos.Summary;
import com.portica.management.analytics.AnalyticsDtos.Timeseries;
import com.portica.management.analytics.AnalyticsDtos.TimeseriesPoint;
import com.portica.management.analytics.AnalyticsDtos.Top;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard queries against a real ClickHouse with the schema portica-analytics creates. */
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsQueryIntegrationTest {

    @Container
    static final GenericContainer<?> clickhouse = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
            .withEnv("CLICKHOUSE_USER", "portica")
            .withEnv("CLICKHOUSE_PASSWORD", "portica")
            .withEnv("CLICKHOUSE_DB", "portica_analytics")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).withStartupTimeout(Duration.ofMinutes(2)));

    static ClickHouseClient client;
    static AnalyticsService analytics;
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    @BeforeAll
    static void seed() {
        String url = "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
        client = new ClickHouseClient(url, "portica_analytics", "portica", "portica", Duration.ofSeconds(10));
        analytics = new AnalyticsService(new AnalyticsStore(client));
        AnalyticsSchema.STATEMENTS.forEach(client::execute);
        List<String> rows = new ArrayList<>();
        // acme: 8 pets calls (one 500, one 404) over the last 20 minutes, 2 orders calls; globex: 5 calls
        for (int i = 0; i < 8; i++) {
            int status = i == 0 ? 500 : i == 1 ? 404 : 200;
            rows.add(row("acme", "dev", "pets", NOW.minusSeconds(150L * i + 30), status, 10 * (i + 1), "shop"));
        }
        rows.add(row("acme", "prod", "orders", NOW.minusSeconds(60), 200, 5, "crm"));
        rows.add(row("acme", "prod", "orders", NOW.minusSeconds(90), 201, 7, ""));
        for (int i = 0; i < 5; i++) {
            rows.add(row("globex", "dev", "pets", NOW.minusSeconds(40), 503, 999, "other"));
        }
        client.insertJsonEachRow("api_events", rows);
    }

    @Test
    void summaryCountsErrorsAndLatencyForOneOrganization() {
        Summary all = analytics.summary("acme", NOW.minus(Duration.ofHours(1)), NOW, null, null);
        assertThat(all.requests()).isEqualTo(10);
        assertThat(all.errors4xx()).isEqualTo(1);
        assertThat(all.errors5xx()).isEqualTo(1);
        assertThat(all.errorRate()).isEqualTo(0.2);
        assertThat(all.p99Ms()).isLessThanOrEqualTo(80);

        Summary pets = analytics.summary("acme", NOW.minus(Duration.ofHours(1)), NOW, "dev", "pets");
        assertThat(pets.requests()).isEqualTo(8);
        assertThat(pets.avgMs()).isEqualTo(45.0);
    }

    @Test
    void timeseriesFillsEveryMinuteOfTheHour() {
        Timeseries series = analytics.timeseries("acme", NOW.minus(Duration.ofHours(1)), NOW, null, null);
        assertThat(series.bucketSeconds()).isEqualTo(60);
        assertThat(series.points()).hasSize(60);
        assertThat(series.points().stream().mapToLong(TimeseriesPoint::requests).sum()).isEqualTo(10);
        assertThat(series.points().stream().mapToLong(TimeseriesPoint::errors).sum()).isEqualTo(2);
    }

    @Test
    void topBreaksDownByAWhitelistedDimension() {
        Top proxies = analytics.top("acme", "api_proxy", NOW.minus(Duration.ofHours(1)), NOW, null, null, 10);
        assertThat(proxies.entries()).extracting(e -> e.name() + "=" + e.requests()).containsExactly("pets=8", "orders=2");
        Top statuses = analytics.top("acme", "status", NOW.minus(Duration.ofHours(1)), NOW, null, null, 2);
        assertThat(statuses.entries()).hasSize(2);
        assertThat(statuses.entries().getFirst().name()).isEqualTo("200");
    }

    @Test
    void eventsAreNewestFirstAndFilterByStatusClass() {
        var errors = analytics.events("acme", NOW.minus(Duration.ofHours(1)), NOW, null, null, "errors", 50);
        assertThat(errors.events()).extracting(e -> e.status()).containsExactly(500, 404);
        var recent = analytics.events("acme", NOW.minus(Duration.ofHours(1)), NOW, null, null, null, 3);
        assertThat(recent.events()).hasSize(3);
        assertThat(recent.events().get(0).time()).isAfterOrEqualTo(recent.events().get(1).time());
    }

    @Test
    void missingTablesReadAsNoData() {
        ClickHouseClient other = new ClickHouseClient("http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123),
                "default", "portica", "portica", Duration.ofSeconds(10));
        AnalyticsService empty = new AnalyticsService(new AnalyticsStore(other));
        assertThat(empty.summary("acme", null, null, null, null).requests()).isZero();
        assertThat(empty.timeseries("acme", null, null, null, null).points()).allMatch(p -> p.requests() == 0);
    }

    private static String row(String org, String env, String proxy, Instant time, int status, int ms, String app) {
        return """
                {"event_time":"%s","request_id":"%s","organization":"%s","environment":"%s","api_proxy":"%s",
                 "revision":1,"method":"GET","path":"/x","status":%d,"total_ms":%d,"target_ms":%d,"target_called":1,
                 "request_bytes":0,"response_bytes":0,"client_ip":"10.0.0.1","app":"%s","developer":"","api_product":"",
                 "fault":"","cache_hit":0,"gateway":"gw"}""".replace("\n", "")
                .formatted(time, java.util.UUID.randomUUID(), org, env, proxy, status, ms, Math.max(1, ms - 2), app);
    }
}

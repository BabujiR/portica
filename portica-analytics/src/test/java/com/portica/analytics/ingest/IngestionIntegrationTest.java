package com.portica.analytics.ingest;

import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.analytics.ClickHouseClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Events produced to Kafka end up in ClickHouse; malformed messages are skipped, not retried forever. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IngestionIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    static final GenericContainer<?> clickhouse = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
            .withEnv("CLICKHOUSE_USER", "portica")
            .withEnv("CLICKHOUSE_PASSWORD", "portica")
            .withEnv("CLICKHOUSE_DB", "portica_analytics")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("portica.analytics.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("portica.analytics.kafka.partitions", () -> "1");
        registry.add("portica.analytics.clickhouse.url", IngestionIntegrationTest::clickHouseUrl);
    }

    static String clickHouseUrl() {
        return "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
    }

    @Test
    void apiEventsAndTracesAreWrittenToClickHouse() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String event = """
                {"event_time":"%s","request_id":"%s","organization":"acme","environment":"dev","api_proxy":"pets",
                 "revision":2,"method":"GET","path":"/pets/1","status":200,"total_ms":12,"target_ms":9,
                 "target_called":1,"request_bytes":0,"response_bytes":42,"client_ip":"10.0.0.1","app":"shop",
                 "developer":"dev@example.com","api_product":"gold","fault":"","cache_hit":0,"gateway":"gw-1",
                 "added_later":"ignored"}""".formatted(Instant.now(), requestId);
        String trace = """
                {"session_id":"s-1","event_time":"%s","request_id":"%s","organization":"acme","environment":"dev",
                 "api_proxy":"pets","status":200,"total_ms":12,"payload":{"steps":[{"policy":"verify"}]}}"""
                .formatted(Instant.now(), requestId);

        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(AnalyticsTopics.API_EVENTS, requestId, "not json")).get();
            producer.send(new ProducerRecord<>(AnalyticsTopics.API_EVENTS, requestId, event)).get();
            producer.send(new ProducerRecord<>(AnalyticsTopics.TRACE_TRANSACTIONS, requestId, trace)).get();
        }

        ClickHouseClient client = new ClickHouseClient(clickHouseUrl(), "portica_analytics", "portica", "portica",
                Duration.ofSeconds(10));
        List<Map<String, Object>> events = awaitRows(client,
                "SELECT api_proxy, status, app, total_ms FROM api_events WHERE request_id = {id:String}", requestId);
        assertThat(events).containsExactly(Map.of("api_proxy", "pets", "status", 200L, "app", "shop", "total_ms", 12L));

        List<Map<String, Object>> traces = awaitRows(client,
                "SELECT session_id, payload FROM trace_transactions WHERE request_id = {id:String}", requestId);
        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst()).containsEntry("session_id", "s-1");
        assertThat((String) traces.getFirst().get("payload")).contains("\"policy\":\"verify\"");
    }

    private static List<Map<String, Object>> awaitRows(ClickHouseClient client, String sql, String id) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (true) {
            try {
                List<Map<String, Object>> rows = client.query(sql, Map.of("id", id));
                if (!rows.isEmpty() || Instant.now().isAfter(deadline)) {
                    return rows;
                }
            } catch (ClickHouseClient.ClickHouseException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;   // table never appeared
                }
            }
            Thread.sleep(250);
        }
    }

    private static KafkaProducer<String, String> producer() {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "60000");   // waits for the ingestor to create the topics
        return new KafkaProducer<>(config);
    }
}

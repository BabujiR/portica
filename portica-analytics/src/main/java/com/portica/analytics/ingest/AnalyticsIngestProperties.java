package com.portica.analytics.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Ingestion settings ({@code portica.analytics.*}).
 *
 * @param enabled    run the Kafka to ClickHouse ingestion
 * @param kafka      where to consume from
 * @param clickhouse where to write to
 */
@ConfigurationProperties(prefix = "portica.analytics")
public record AnalyticsIngestProperties(@DefaultValue("true") boolean enabled,
                                        @DefaultValue Kafka kafka,
                                        @DefaultValue ClickHouse clickhouse) {

    /**
     * @param bootstrapServers  Kafka brokers
     * @param groupId           consumer group (instances in one group share the partitions)
     * @param partitions        partitions for topics created at startup
     * @param replicationFactor replication for topics created at startup (1 for a single broker)
     * @param maxPollRecords    largest batch written to ClickHouse in one insert
     */
    public record Kafka(@DefaultValue("localhost:9092") String bootstrapServers,
                        @DefaultValue("portica-analytics") String groupId,
                        @DefaultValue("3") int partitions,
                        @DefaultValue("1") short replicationFactor,
                        @DefaultValue("1000") int maxPollRecords) {
    }

    public record ClickHouse(@DefaultValue("http://localhost:8123") String url,
                             @DefaultValue("portica_analytics") String database,
                             @DefaultValue("portica") String user,
                             @DefaultValue("portica") String password,
                             @DefaultValue("30s") Duration timeout) {
    }
}

package com.portica.management.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Where analytics and trace data live ({@code portica.clickhouse.*}); written by portica-analytics. */
@ConfigurationProperties(prefix = "portica.clickhouse")
public record ClickHouseProperties(@DefaultValue("http://localhost:8123") String url,
                                   @DefaultValue("portica_analytics") String database,
                                   @DefaultValue("portica") String user,
                                   @DefaultValue("portica") String password,
                                   @DefaultValue("15s") Duration timeout) {
}

package com.portica.management.analytics;

import com.portica.common.analytics.ClickHouseClient;
import com.portica.common.analytics.ClickHouseClient.ClickHouseException;
import com.portica.common.error.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read access to ClickHouse. Missing tables (nothing ingested yet) read as no rows; an unreachable
 * or failing store becomes a 503 without leaking the SQL or the store's error text to callers.
 */
@Component
public class AnalyticsStore {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStore.class);

    private final ClickHouseClient client;

    @Autowired
    public AnalyticsStore(ClickHouseProperties properties) {
        this(new ClickHouseClient(properties.url(), properties.database(), properties.user(), properties.password(),
                properties.timeout()));
    }

    AnalyticsStore(ClickHouseClient client) {
        this.client = client;
    }

    public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
        try {
            return client.query(sql, params);
        } catch (ClickHouseException e) {
            if (e.unknownTable() || (e.getMessage() != null && e.getMessage().contains("UNKNOWN_DATABASE"))) {
                return List.of();
            }
            log.warn("Analytics query failed: {}", e.getMessage());
            throw new ServiceUnavailableException("Analytics data is unavailable right now. Try again shortly.");
        }
    }
}

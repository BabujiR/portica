package com.portica.common.analytics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Minimal client for ClickHouse's HTTP interface (port 8123): no driver, just the JDK HTTP client.
 * Queries use server-side parameters ({@code {name:Type}} in SQL, values sent as {@code param_name}),
 * so user input never becomes part of the SQL text.
 */
public class ClickHouseClient {

    /** A ClickHouse error, with its message (never contains credentials). */
    public static class ClickHouseException extends RuntimeException {
        private final int status;

        public ClickHouseException(int status, String message) {
            super(message);
            this.status = status;
        }

        public ClickHouseException(String message, Throwable cause) {
            super(message, cause);
            this.status = 0;
        }

        /** HTTP status from ClickHouse; 0 when it couldn't be reached. */
        public int status() {
            return status;
        }

        /**
         * Table or database doesn't exist (nothing ingested yet). ClickHouse answers those with 404;
         * the error code is checked too because the message is truncated before its "(UNKNOWN_TABLE)".
         */
        public boolean unknownTable() {
            String m = getMessage();
            return status == 404 || (m != null && (m.contains("UNKNOWN_TABLE") || m.contains("UNKNOWN_DATABASE")
                    || m.contains("Code: 60.") || m.contains("Code: 81.")));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI baseUri;
    private final String database;
    private final String authorization;
    private final HttpClient http;
    private final Duration timeout;

    public ClickHouseClient(String url, String database, String user, String password, Duration timeout) {
        this.baseUri = URI.create(url.endsWith("/") ? url : url + "/");
        this.database = database;
        this.authorization = user == null || user.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString((user + ":" + (password == null ? "" : password))
                .getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Runs a statement that returns nothing (DDL). */
    public void execute(String sql) {
        send(sql, Map.of(), Map.of());
    }

    /** Creates the configured database when it doesn't exist (runs against {@code default}). */
    public void createDatabaseIfMissing() {
        if (!database.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database name");
        }
        send("CREATE DATABASE IF NOT EXISTS " + database, Map.of(), Map.of(), "default");
    }

    /** True when ClickHouse answers. */
    public boolean ping() {
        try {
            send("SELECT 1", Map.of(), Map.of(), "default");
            return true;
        } catch (ClickHouseException e) {
            return false;
        }
    }

    /**
     * Inserts JSON rows ({@code FORMAT JSONEachRow}). Unknown fields are ignored and ISO-8601 times are
     * accepted, so producers can add fields before the table has them.
     */
    public void insertJsonEachRow(String table, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid table name");
        }
        StringBuilder body = new StringBuilder("INSERT INTO ").append(table).append(" FORMAT JSONEachRow\n");
        rows.forEach(r -> body.append(r).append('\n'));
        send(body.toString(), Map.of(), Map.of(
                "input_format_skip_unknown_fields", "1",
                "date_time_input_format", "best_effort"));
    }

    /** Runs a query and returns its rows (column name to value; numbers as numbers). */
    public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
        String response = send(sql + "\nFORMAT JSON", params, Map.of("output_format_json_quote_64bit_integers", "0"));
        JsonNode data = JSON.readTree(response).path("data");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : data) {
            Map<String, Object> map = new LinkedHashMap<>();
            row.properties().forEach(e -> map.put(e.getKey(), value(e.getValue())));
            rows.add(map);
        }
        return rows;
    }

    private static Object value(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isNumber()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray() || node.isObject()) return JSON.convertValue(node, Object.class);
        return node.asString();
    }

    private String send(String body, Map<String, ?> params, Map<String, String> settings) {
        return send(body, params, settings, database);
    }

    private String send(String body, Map<String, ?> params, Map<String, String> settings, String db) {
        StringJoiner query = new StringJoiner("&");
        query.add("database=" + encode(db));
        settings.forEach((k, v) -> query.add(encode(k) + "=" + encode(v)));
        params.forEach((k, v) -> query.add("param_" + encode(k) + "=" + encode(String.valueOf(v))));
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve("?" + query))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                String message = response.body() == null ? "" : response.body().strip();
                throw new ClickHouseException(response.statusCode(),
                        "ClickHouse returned " + response.statusCode() + ": " + truncate(message));
            }
            return response.body();
        } catch (IOException e) {
            throw new ClickHouseException("ClickHouse is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClickHouseException("Interrupted", e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}

package com.portica.management.secrets;

import com.portica.management.kvm.KvmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Keeps every stored secret's data key wrapped by the current key-encryption key. After a KEK rotation
 * (a new local active key, or a new Vault Transit key version) it re-wraps data keys row by row; the
 * encrypted values and the runtime configuration don't change. Runs in the background and on demand.
 */
@Service
public class SecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(SecretRotationService.class);

    /** Every column holding a secret encrypted by {@link KvmCipher}. */
    record SecretColumn(String table, String column, String where) {
    }

    static final List<SecretColumn> COLUMNS = List.of(
            new SecretColumn("key_value_map_entries", "entry_value",
                    "map_id IN (SELECT id FROM key_value_maps WHERE encrypted)"),
            new SecretColumn("ai_providers", "api_key_encrypted", null),
            new SecretColumn("mcp_servers", "secret_encrypted", null),
            new SecretColumn("mcp_servers", "access_token_encrypted", null),
            new SecretColumn("mcp_servers", "refresh_token_encrypted", null),
            new SecretColumn("target_servers", "tls_client_key_encrypted", null));

    public record Status(String backend, boolean enabled, Map<String, Long> valuesByKey, long needingRewrap) {
    }

    public record RewrapResult(String backend, long checked, long rewrapped, long failed) {
    }

    private final JdbcClient jdbc;
    private final KvmCipher cipher;
    private final SecretsProperties properties;

    public SecretRotationService(JdbcClient jdbc, KvmCipher cipher, SecretsProperties properties) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.properties = properties;
    }

    public Status status() {
        if (!cipher.enabled()) {
            return new Status("none", false, Map.of(), 0);
        }
        Map<String, Long> byKey = new TreeMap<>();
        long stale = 0;
        for (SecretColumn c : COLUMNS) {
            for (Row row : rows(c)) {
                byKey.merge(cipher.keyOf(row.value()), 1L, Long::sum);
                if (cipher.needsRewrap(row.value())) {
                    stale++;
                }
            }
        }
        return new Status(cipher.wrapper().describe(), true, byKey, stale);
    }

    /** Creates a new KEK version in the backend (Vault), then re-wraps everything. */
    public RewrapResult rotateKey() {
        cipher.requireEnabled();
        cipher.wrapper().rotate();
        return rewrapAll();
    }

    /** Re-wraps every data key not under the current KEK. Each row is updated only if it didn't change meanwhile. */
    public RewrapResult rewrapAll() {
        cipher.requireEnabled();
        long checked = 0;
        long rewrapped = 0;
        long failed = 0;
        for (SecretColumn c : COLUMNS) {
            for (Row row : rows(c)) {
                checked++;
                try {
                    if (!cipher.needsRewrap(row.value())) {
                        continue;
                    }
                    String next = cipher.rewrap(row.value());
                    int updated = jdbc.sql("UPDATE " + c.table() + " SET " + c.column() + " = :next WHERE id = :id AND "
                                    + c.column() + " = :previous")
                            .param("next", next).param("id", row.id()).param("previous", row.value()).update();
                    rewrapped += updated;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Couldn't re-wrap {}.{} {}: {}", c.table(), c.column(), row.id(), e.getMessage());
                }
            }
        }
        if (rewrapped > 0 || failed > 0) {
            log.info("Re-wrapped {} of {} stored secrets with {} ({} failed)", rewrapped, checked, cipher.wrapper().describe(), failed);
        }
        return new RewrapResult(cipher.wrapper().describe(), checked, rewrapped, failed);
    }

    @Scheduled(initialDelayString = "${portica.secrets.rotation.initial-delay:PT1M}",
            fixedDelayString = "${portica.secrets.rotation.interval:PT6H}")
    void scheduledRewrap() {
        if (!properties.rotation().enabled() || !cipher.enabled()) {
            return;
        }
        try {
            rewrapAll();
        } catch (RuntimeException e) {
            log.warn("Background secret re-wrap failed: {}", e.getMessage());
        }
    }

    private record Row(UUID id, String value) {
    }

    private List<Row> rows(SecretColumn c) {
        return jdbc.sql("SELECT id, " + c.column() + " AS v FROM " + c.table() + " WHERE " + c.column() + " IS NOT NULL"
                        + (c.where() == null ? "" : " AND " + c.where()))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("v")))
                .list();
    }

    /** For the status endpoint. */
    static Map<String, Object> describe(Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", s.backend());
        m.put("valuesByKey", s.valuesByKey());
        m.put("needingRewrap", s.needingRewrap());
        return m;
    }
}

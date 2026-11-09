package com.portica.management.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data keys wrapped by HashiCorp Vault's Transit secrets engine: the KEK never leaves Vault. Wrapped
 * form is Vault's own ciphertext, {@code vault:v<N>:...}. Rotation is {@code transit/keys/<key>/rotate}
 * followed by {@code transit/rewrap}, neither of which exposes a data key.
 * <p>
 * The Vault policy for Portica needs {@code update} on {@code <mount>/encrypt/<key>},
 * {@code <mount>/decrypt/<key>}, {@code <mount>/rewrap/<key>}, {@code <mount>/keys/<key>/rotate} and
 * {@code read} on {@code <mount>/keys/<key>}.
 */
public class VaultTransitKeyWrapper implements KeyWrapper {

    private static final Logger log = LoggerFactory.getLogger(VaultTransitKeyWrapper.class);
    private static final Duration VERSION_TTL = Duration.ofSeconds(60);

    private final SecretsProperties.Vault settings;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, byte[]> cache;
    private volatile String token;
    private volatile int latestVersion;
    private volatile Instant versionCheckedAt = Instant.EPOCH;

    public VaultTransitKeyWrapper(SecretsProperties.Vault settings) {
        if (settings.address() == null || settings.address().isBlank()) {
            throw new IllegalStateException("portica.secrets.vault.address is required with provider vault");
        }
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.token = readToken();
        int size = Math.max(0, settings.cacheSize());
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > size;
            }
        });
    }

    @Override
    public String wrap(byte[] dataKey) {
        JsonNode data = call("POST", "encrypt/" + settings.keyName(),
                Map.of("plaintext", Base64.getEncoder().encodeToString(dataKey)));
        String ciphertext = data.path("ciphertext").asString();
        cache.put(ciphertext, dataKey.clone());
        return ciphertext;
    }

    @Override
    public byte[] unwrap(String wrapped) {
        byte[] cached = cache.get(wrapped);
        if (cached != null) {
            return cached.clone();
        }
        JsonNode data = call("POST", "decrypt/" + settings.keyName(), Map.of("ciphertext", wrapped));
        byte[] key = Base64.getDecoder().decode(data.path("plaintext").asString());
        cache.put(wrapped, key.clone());
        return key;
    }

    @Override
    public String rewrap(String wrapped) {
        return call("POST", "rewrap/" + settings.keyName(), Map.of("ciphertext", wrapped)).path("ciphertext").asString();
    }

    @Override
    public boolean isCurrent(String wrapped) {
        return wrapped.startsWith("vault:v" + latestVersion() + ":");
    }

    @Override
    public void rotate() {
        call("POST", "keys/" + settings.keyName() + "/rotate", Map.of());
        versionCheckedAt = Instant.EPOCH;
        log.info("Rotated Vault transit key {}/{} to v{}", settings.transitMount(), settings.keyName(), latestVersion());
    }

    @Override
    public String describe() {
        return "vault " + settings.transitMount() + "/" + settings.keyName() + " v" + latestVersion();
    }

    int latestVersion() {
        if (Instant.now().isAfter(versionCheckedAt.plus(VERSION_TTL))) {
            latestVersion = call("GET", "keys/" + settings.keyName(), null).path("latest_version").asInt(1);
            versionCheckedAt = Instant.now();
        }
        return latestVersion;
    }

    private JsonNode call(String method, String path, Map<String, Object> body) {
        HttpResponse<String> response = send(method, path, body);
        if (response.statusCode() == 403 && settings.tokenFile() != null && !settings.tokenFile().isBlank()) {
            token = readToken();     // the agent may have renewed it
            response = send(method, path, body);
        }
        if (response.statusCode() / 100 != 2) {
            String errors = "";
            try {
                errors = json.readTree(response.body()).path("errors").toString();
            } catch (RuntimeException ignored) {
                // not JSON
            }
            throw new SecretsException("Vault " + path + " returned " + response.statusCode() + " " + errors);
        }
        if (response.body() == null || response.body().isBlank()) {
            return json.createObjectNode();
        }
        return json.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String method, String path, Map<String, Object> body) {
        String base = settings.address().endsWith("/") ? settings.address().substring(0, settings.address().length() - 1)
                : settings.address();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/" + settings.transitMount() + "/" + path))
                .timeout(settings.timeout())
                .header("X-Vault-Token", token == null ? "" : token)
                .header("Content-Type", "application/json");
        if (settings.namespace() != null && !settings.namespace().isBlank()) {
            request.header("X-Vault-Namespace", settings.namespace());
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SecretsException("Vault is unreachable at " + settings.address() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsException("Interrupted", e);
        }
    }

    private String readToken() {
        if (settings.tokenFile() != null && !settings.tokenFile().isBlank()) {
            try {
                return Files.readString(Path.of(settings.tokenFile()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Can't read portica.secrets.vault.token-file: " + e.getMessage(), e);
            }
        }
        return settings.token();
    }
}

package com.portica.common.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * Keeps one runtime document per environment in step with the management plane, like the gateway's
 * synchronizer: poll the version index, fetch {@code /internal/v1/runtime/organizations/{org}/environments/{env}/{suffix}}
 * for environments whose version moved, drop environments that disappeared. Serves the last good copy when
 * the management plane is unreachable.
 */
public class RuntimeFeed<T> {

    private static final Logger log = LoggerFactory.getLogger(RuntimeFeed.class);

    /** Same shape as management's RuntimeIndex. */
    public record Index(List<EnvironmentVersion> environments) {
    }

    public record EnvironmentVersion(String organization, String environment, long configVersion) {
    }

    private record Entry<T>(long version, T document) {
    }

    private final ManagementApiClient client;
    private final String suffix;
    private final Class<T> type;
    private final BiPredicate<String, String> selector;
    private volatile Map<String, Entry<T>> entries = Map.of();
    private ScheduledExecutorService scheduler;

    public RuntimeFeed(ManagementApiClient client, String suffix, Class<T> type, BiPredicate<String, String> selector) {
        this.client = client;
        this.suffix = suffix;
        this.type = type;
        this.selector = selector == null ? (o, e) -> true : selector;
    }

    public Optional<T> get(String organization, String environment) {
        Entry<T> e = entries.get(organization + "/" + environment);
        return e == null ? Optional.empty() : Optional.of(e.document());
    }

    /** Puts a document directly (tests, or when syncing is off). */
    public synchronized void put(String organization, String environment, T document) {
        Map<String, Entry<T>> next = new HashMap<>(entries);
        next.put(organization + "/" + environment, new Entry<>(Long.MAX_VALUE, document));
        entries = Map.copyOf(next);
    }

    public synchronized void start(Duration interval) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runtime-feed-" + suffix);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                syncOnce();
            } catch (RuntimeException e) {
                log.warn("Runtime sync ({}) failed, serving last known config: {}", suffix, e.getMessage());
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** One sync round. */
    public void syncOnce() {
        Index index = client.get("/internal/v1/runtime/environments", Index.class).orElse(new Index(List.of()));
        Map<String, Entry<T>> current = entries;
        Map<String, Entry<T>> next = new HashMap<>();
        Set<String> wanted = new HashSet<>();
        for (EnvironmentVersion v : index.environments() == null ? List.<EnvironmentVersion>of() : index.environments()) {
            if (!selector.test(v.organization(), v.environment())) {
                continue;
            }
            String key = v.organization() + "/" + v.environment();
            wanted.add(key);
            Entry<T> existing = current.get(key);
            if (existing != null && existing.version() == v.configVersion()) {
                next.put(key, existing);
                continue;
            }
            try {
                client.get("/internal/v1/runtime/organizations/" + v.organization() + "/environments/" + v.environment()
                                + "/" + suffix, type)
                        .ifPresent(doc -> next.put(key, new Entry<>(v.configVersion(), doc)));
            } catch (RuntimeException e) {
                log.warn("Could not fetch {} config for {}: {}", suffix, key, e.getMessage());
                if (existing != null) {
                    next.put(key, existing);
                }
            }
        }
        current.forEach((key, entry) -> {
            if (entry.version() == Long.MAX_VALUE && !wanted.contains(key)) {
                next.putIfAbsent(key, entry);    // put() by hand: keep
            }
        });
        entries = Map.copyOf(next);
    }
}

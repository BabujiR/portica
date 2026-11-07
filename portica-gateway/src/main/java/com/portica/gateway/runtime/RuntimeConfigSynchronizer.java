package com.portica.gateway.runtime;

import com.portica.gateway.config.PorticaGatewayProperties;
import com.portica.gateway.runtime.RuntimeModel.EnvironmentVersion;
import com.portica.gateway.runtime.RuntimeModel.RuntimeIndex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the gateway in step with the management plane by polling.
 * <ol>
 *   <li>Every poll interval, fetch the small version index.</li>
 *   <li>For each served environment whose version moved, fetch its full snapshot.</li>
 *   <li>Drop environments that disappeared (deleted, archived, or no longer selected).</li>
 * </ol>
 * If the management plane is unreachable the gateway keeps serving its last known good config.
 */
@Component
public class RuntimeConfigSynchronizer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigSynchronizer.class);

    private final PorticaGatewayProperties properties;
    private final ManagementClient management;
    private final RuntimeConfigRegistry registry;
    private final EnvironmentSelector selector;
    private final Counter syncFailures;

    private volatile Disposable subscription;
    private volatile Instant lastSuccess;

    public RuntimeConfigSynchronizer(PorticaGatewayProperties properties, ManagementClient management,
                                     RuntimeConfigRegistry registry, ObjectProvider<MeterRegistry> meters) {
        this.properties = properties;
        this.management = management;
        this.registry = registry;
        this.selector = new EnvironmentSelector(properties.environments());
        MeterRegistry meterRegistry = meters.getIfAvailable();
        if (meterRegistry != null) {
            Gauge.builder("portica.gateway.routes", registry, RuntimeConfigRegistry::routeCount)
                    .description("Routes currently served from Portica deployments")
                    .register(meterRegistry);
            Gauge.builder("portica.gateway.config.age.seconds", this, RuntimeConfigSynchronizer::secondsSinceLastSuccess)
                    .description("Seconds since configuration was last synced successfully")
                    .register(meterRegistry);
            this.syncFailures = Counter.builder("portica.gateway.config.sync.failures").register(meterRegistry);
        } else {
            this.syncFailures = null;
        }
    }

    @Override
    public void start() {
        if (!properties.sync().enabled()) {
            log.info("Runtime config sync is disabled (portica.gateway.sync.enabled=false)");
            return;
        }
        log.info("Syncing runtime config from {} every {} for environments {}", properties.managementUrl(),
                properties.sync().pollInterval(), properties.environments());
        subscription = Flux.interval(java.time.Duration.ZERO, properties.sync().pollInterval(), Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(tick -> syncOnce()
                        .doOnSuccess(v -> lastSuccess = Instant.now())
                        .onErrorResume(e -> {
                            if (syncFailures != null) {
                                syncFailures.increment();
                            }
                            log.warn("Runtime config sync failed (serving last known config): {}", e.toString());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    /** One sync round. Package-visible for tests. */
    Mono<Void> syncOnce() {
        return management.index().flatMap(this::reconcile);
    }

    private Mono<Void> reconcile(RuntimeIndex index) {
        Map<String, Long> known = registry.versions();
        Set<String> wanted = new HashSet<>();
        List<EnvironmentVersion> changed = new ArrayList<>();
        for (EnvironmentVersion env : index.environments()) {
            if (!selector.matches(env.organization(), env.environment())) {
                continue;
            }
            wanted.add(env.key());
            Long current = known.get(env.key());
            if (current == null || current != env.configVersion()) {
                changed.add(env);
            }
        }
        Set<String> gone = new HashSet<>(known.keySet());
        gone.removeAll(wanted);

        return Flux.fromIterable(changed)
                .concatMap(env -> management.snapshot(env.organization(), env.environment())
                        .doOnNext(snapshot -> snapshot.ifPresentOrElse(registry::apply,
                                () -> gone.add(env.key())))
                        // one broken environment must not block the others
                        .onErrorResume(e -> {
                            log.warn("Could not fetch config for {}: {}", env.key(), e.toString());
                            return Mono.empty();
                        }))
                .then(Mono.fromRunnable(() -> registry.remove(gone)));
    }

    private double secondsSinceLastSuccess() {
        Instant last = lastSuccess;
        return last == null ? -1 : java.time.Duration.between(last, Instant.now()).toSeconds();
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
        subscription = null;
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }
}

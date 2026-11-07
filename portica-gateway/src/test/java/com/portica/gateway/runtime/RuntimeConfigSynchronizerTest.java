package com.portica.gateway.runtime;

import com.portica.gateway.config.PorticaGatewayProperties;
import com.portica.gateway.runtime.RuntimeModel.EnvironmentVersion;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeIndex;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigSynchronizerTest {

    private final ManagementClient management = mock(ManagementClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RuntimeConfigRegistry registry = new RuntimeConfigRegistry(events);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> noMetrics = mock(ObjectProvider.class);

    private final RuntimeConfigSynchronizer synchronizer = new RuntimeConfigSynchronizer(
            new PorticaGatewayProperties("http://mgmt", List.of("acme/*"),
                    new PorticaGatewayProperties.Sync(false, null, null), null),
            management, registry, noMetrics);

    @Test
    void fetchesOnlySelectedAndChangedEnvironments() {
        index(version("acme", "dev", 1), version("globex", "dev", 1));
        snapshot("acme", "dev", 1);

        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        assertThat(registry.versions()).containsOnlyKeys("acme/dev");
        verify(management, never()).snapshot("globex", "dev");
        verify(events).publishEvent(any(RefreshRoutesEvent.class));

        // Same version again: no re-fetch, no route refresh.
        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();
        verify(management, times(1)).snapshot("acme", "dev");
    }

    @Test
    void refetchesWhenVersionMovesAndDropsVanishedEnvironments() {
        index(version("acme", "dev", 1), version("acme", "prod", 1));
        snapshot("acme", "dev", 1);
        snapshot("acme", "prod", 1);
        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        index(version("acme", "dev", 2)); // dev changed, prod deleted
        snapshot("acme", "dev", 2);
        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        assertThat(registry.versions()).containsExactlyEntriesOf(java.util.Map.of("acme/dev", 2L));
    }

    @Test
    void environmentReturning404IsRemoved() {
        index(version("acme", "dev", 1));
        snapshot("acme", "dev", 1);
        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        index(version("acme", "dev", 2));
        when(management.snapshot("acme", "dev")).thenReturn(Mono.just(Optional.empty()));
        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        assertThat(registry.versions()).isEmpty();
    }

    @Test
    void failureFetchingOneEnvironmentDoesNotBlockOthers() {
        index(version("acme", "dev", 1), version("acme", "prod", 1));
        when(management.snapshot("acme", "dev")).thenReturn(Mono.error(new IllegalStateException("boom")));
        snapshot("acme", "prod", 1);

        StepVerifier.create(synchronizer.syncOnce()).verifyComplete();

        assertThat(registry.versions()).containsOnlyKeys("acme/prod");
    }

    @Test
    void indexFailurePropagatesSoTheLoopKeepsLastKnownConfig() {
        when(management.index()).thenReturn(Mono.error(new IllegalStateException("management down")));

        StepVerifier.create(synchronizer.syncOnce()).verifyError(IllegalStateException.class);
        verify(management, never()).snapshot(anyString(), anyString());
    }

    private void index(EnvironmentVersion... versions) {
        when(management.index()).thenReturn(Mono.just(new RuntimeIndex(Instant.now(), List.of(versions))));
    }

    private void snapshot(String org, String env, long version) {
        when(management.snapshot(org, env)).thenReturn(Mono.just(Optional.of(
                new RuntimeEnvironmentConfig(org, env, version, Instant.now(), List.of()))));
    }

    private static EnvironmentVersion version(String org, String env, long version) {
        return new EnvironmentVersion(org, env, version);
    }
}

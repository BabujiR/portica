package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.KeyValueMapOperationsConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * KeyValueMapOperations (Get): copies entries of a key value map into variables. The map's entries
 * come with the environment snapshot, so lookups are in memory; a changed entry reaches the gateway on
 * its next sync. Values of encrypted maps may only be assigned to {@code private.*} variables
 * (checked when the bundle is compiled; such a proxy is not served otherwise). A missing map or entry
 * yields the {@code defaultValue} (or no value).
 */
public final class KeyValueMapOperationsPolicy implements Policy {

    private record CompiledGet(MessageTemplate key, String assignTo, String defaultValue) {
    }

    private final String name;
    private final Map<String, String> entries;
    private final List<CompiledGet> gets;

    public KeyValueMapOperationsPolicy(String name, KeyValueMapOperationsConfig config, RuntimeKeyValueMap map) {
        this.name = name;
        this.entries = map == null ? Map.of() : map.entries();
        if (map != null && map.encrypted()) {
            config.get().stream()
                    .filter(g -> !g.assignTo().startsWith(KeyValueMapOperationsConfig.PRIVATE_PREFIX))
                    .findFirst()
                    .ifPresent(g -> {
                        throw new IllegalStateException("policy '" + name + "' assigns encrypted key value map '"
                                + config.mapName() + "' to '" + g.assignTo() + "': use a private.* variable");
                    });
        }
        this.gets = config.get().stream()
                .map(g -> new CompiledGet(MessageTemplate.of(g.key()), g.assignTo(), g.defaultValue())).toList();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        for (CompiledGet g : gets) {
            String value = entries.get(ctx.render(g.key()));
            ctx.setVariable(g.assignTo(), value != null ? value : g.defaultValue());
        }
        return Mono.empty();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.KEY_VALUE_MAP_OPERATIONS;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            KeyValueMapOperationsConfig c = (KeyValueMapOperationsConfig) config;
            return new KeyValueMapOperationsPolicy(name, c, context.keyValueMap(c.scope(), c.mapName()));
        }
    }
}

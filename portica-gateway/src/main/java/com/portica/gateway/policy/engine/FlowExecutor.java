package com.portica.gateway.policy.engine;

import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** Runs steps in order: evaluate the condition, execute the policy, stop on a fault or short-circuit. */
public final class FlowExecutor {

    private static final Logger log = LoggerFactory.getLogger(FlowExecutor.class);

    private FlowExecutor() {
    }

    public static Mono<Void> run(List<CompiledStep> steps, MessageContext ctx) {
        if (steps == null || steps.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(steps).concatMap(step -> Mono.defer(() -> runStep(step, ctx))).then();
    }

    private static Mono<Void> runStep(CompiledStep step, MessageContext ctx) {
        if (ctx.isShortCircuited() && ctx.phase() == PolicyPhase.REQUEST) {
            return Mono.empty();
        }
        if (!step.condition().test(ctx.resolver())) {
            return Mono.empty();
        }
        String name = step.policy().name();
        Mono<Void> execution = Mono.defer(() -> step.policy().execute(ctx))
                .onErrorMap(e -> !(e instanceof PolicyFault), e -> {
                    log.error("Policy '{}' failed unexpectedly (request {})", name, ctx.requestId(), e);
                    return PolicyFault.of("PolicyExecutionFailed", 500, "Policy '" + name + "' failed");
                });
        if (step.continueOnError()) {
            execution = execution.onErrorResume(PolicyFault.class, fault -> {
                ctx.setVariable(name + ".failed", true);
                ctx.setVariable(name + ".fault.name", fault.faultName());
                return Mono.empty();
            });
        }
        return execution;
    }
}

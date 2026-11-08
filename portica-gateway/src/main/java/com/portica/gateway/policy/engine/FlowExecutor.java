package com.portica.gateway.policy.engine;

import com.portica.common.policy.condition.Condition;
import com.portica.gateway.trace.TraceRecorder;
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
        return run(steps, ctx, null);
    }

    /** @param flow label for traces, e.g. {@code PreFlow request}; null inside shared flows */
    public static Mono<Void> run(List<CompiledStep> steps, MessageContext ctx, String flow) {
        if (steps == null || steps.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(steps).concatMap(step -> Mono.defer(() -> {
            TraceRecorder trace = ctx.trace();
            return trace == null ? runStep(step, ctx) : traced(step, ctx, trace, flow);
        })).then();
    }

    private static Mono<Void> traced(CompiledStep step, MessageContext ctx, TraceRecorder trace, String flow) {
        String name = step.policy().name();
        String condition = step.condition() == Condition.ALWAYS ? null : step.condition().toString();
        String label = flow == null ? "shared flow" : flow;
        if (ctx.isShortCircuited() && ctx.phase() == PolicyPhase.REQUEST) {
            return Mono.empty();
        }
        if (!step.condition().test(ctx.resolver())) {
            trace.step(label, name, step.policyType(), condition, "skipped", 0, null);
            return Mono.empty();
        }
        long start = System.nanoTime();
        Object failedBefore = ctx.variable(name + ".failed");
        return runStep(step, ctx)
                .doOnSuccess(v -> {
                    boolean continued = failedBefore == null && ctx.variable(name + ".failed") != null;
                    trace.step(label, name, step.policyType(), condition, continued ? "continued" : "executed",
                            System.nanoTime() - start, continued ? String.valueOf(ctx.variable(name + ".fault.name")) : null);
                })
                .doOnError(PolicyFault.class, fault -> trace.step(label, name, step.policyType(), condition, "fault",
                        System.nanoTime() - start, fault.faultName()));
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

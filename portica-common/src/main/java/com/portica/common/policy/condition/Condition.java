package com.portica.common.policy.condition;

import java.util.Set;

/** A compiled, thread-safe condition. Create with {@link ConditionParser#parse(String)}. */
public interface Condition {

    Condition ALWAYS = new Condition() {
        @Override
        public boolean test(VariableResolver variables) {
            return true;
        }

        @Override
        public Set<String> variables() {
            return Set.of();
        }

        @Override
        public String toString() {
            return "true";
        }
    };

    /** Evaluates the condition. Never throws: evaluation problems count as false. */
    boolean test(VariableResolver variables);

    /** Variable names the condition reads (used e.g. to decide whether a body must be buffered). */
    Set<String> variables();
}

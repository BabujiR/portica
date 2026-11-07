package com.portica.common.policy.condition;

/** Looks up flow variables by name (e.g. {@code request.header.x-api-key}). Returns null when unset. */
@FunctionalInterface
public interface VariableResolver {

    Object resolve(String name);

    VariableResolver EMPTY = name -> null;
}

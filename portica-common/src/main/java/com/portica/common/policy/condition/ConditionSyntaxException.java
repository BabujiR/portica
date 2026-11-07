package com.portica.common.policy.condition;

public class ConditionSyntaxException extends RuntimeException {

    public ConditionSyntaxException(String message, String condition, int position) {
        super("%s at position %d in condition: %s".formatted(message, position + 1, condition));
    }
}

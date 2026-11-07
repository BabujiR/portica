package com.portica.common.error;

public class BusinessRuleViolationException extends PorticaException {

    public BusinessRuleViolationException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}

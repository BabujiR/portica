package com.portica.management.proxy;

import com.portica.common.error.ErrorCode;
import com.portica.common.error.PorticaException;

import java.util.List;

/** A policy bundle (policies + flows) that failed validation. Rendered as 400 with an {@code errors} list. */
public class InvalidBundleException extends PorticaException {

    private final List<String> problems;

    public InvalidBundleException(List<String> problems) {
        super(ErrorCode.VALIDATION_FAILED, "The policy bundle is invalid (" + problems.size() + " problem"
                + (problems.size() == 1 ? "" : "s") + ")");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

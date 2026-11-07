package com.portica.common.policy.config;

import java.util.List;
import java.util.Map;

/**
 * RaiseFault: stops processing and returns a custom response (usually attached with a condition).
 * Inside a fault rule it replaces the error response instead.
 *
 * @param statusCode  default 500
 * @param headers     response headers (templates)
 * @param body        response body (template)
 * @param contentType default {@code application/json} when a body is set
 * @param faultName   value of {@code fault.name} for fault rules (default {@code RaiseFault})
 */
public record RaiseFaultConfig(Integer statusCode, Map<String, String> headers, String body, String contentType,
                               String faultName) implements PolicyConfig {

    public RaiseFaultConfig {
        statusCode = statusCode == null ? 500 : statusCode;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        contentType = contentType == null ? "application/json" : contentType;
        faultName = faultName == null || faultName.isBlank() ? "RaiseFault" : faultName;
    }

    @Override
    public List<String> validate() {
        return new Problems().range(statusCode, 100, 599, "statusCode").headerNames(headers.keySet(), "headers").list();
    }
}

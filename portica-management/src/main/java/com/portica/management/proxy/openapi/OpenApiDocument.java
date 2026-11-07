package com.portica.management.proxy.openapi;

import java.util.List;

/**
 * The parts of an OpenAPI 3.x / Swagger 2.0 document Portica uses.
 *
 * @param specVersion  e.g. {@code 3.1.0} or {@code 2.0}
 * @param title        {@code info.title}
 * @param apiVersion   {@code info.version}
 * @param description  {@code info.description}
 * @param serverUrl    first absolute http(s) server URL, or null if none is usable as a target
 * @param operations   every operation, in document order
 */
public record OpenApiDocument(String specVersion, String title, String apiVersion, String description,
                              String serverUrl, List<Operation> operations) {

    public OpenApiDocument {
        operations = List.copyOf(operations);
    }

    /** One HTTP operation. Phase 7 turns these into MCP tools. */
    public record Operation(String method, String path, String operationId, String summary) {
    }
}

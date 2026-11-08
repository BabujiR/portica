package com.portica.aigateway.chat;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error answered in OpenAI's format, so OpenAI SDKs surface it properly:
 * {@code {"error": {"message", "type", "code"}}}.
 */
public class AiError extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public AiError(int status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public static AiError invalid(String code, String message) {
        return new AiError(400, "invalid_request_error", code, message);
    }

    public static AiError notFound(String message) {
        return new AiError(404, "invalid_request_error", "not_found", message);
    }

    public AiError header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String type() {
        return type;
    }

    public Map<String, Object> body() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", getMessage());
        error.put("type", type);
        error.put("code", code);
        return Map.of("error", error);
    }

    public Mono<ServerResponse> toResponse() {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON);
        headers.forEach(builder::header);
        return builder.bodyValue(body());
    }
}

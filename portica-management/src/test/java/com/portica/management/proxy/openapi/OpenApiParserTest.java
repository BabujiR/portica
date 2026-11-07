package com.portica.management.proxy.openapi;

import com.portica.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiParserTest {

    private final OpenApiParser parser = new OpenApiParser();

    @Test
    void readsOpenApi3Yaml() {
        OpenApiDocument doc = parser.parse("""
                openapi: 3.1.0
                info:
                  title: Orders
                  version: 2.4.0
                servers:
                  - url: /relative-is-ignored
                  - url: https://orders.example.com/v2
                paths:
                  /orders:
                    get: { operationId: listOrders, summary: List orders }
                    post: { operationId: createOrder }
                    x-internal: true
                  /orders/{id}:
                    parameters: []
                    get: { operationId: getOrder }
                """);

        assertThat(doc.specVersion()).isEqualTo("3.1.0");
        assertThat(doc.title()).isEqualTo("Orders");
        assertThat(doc.apiVersion()).isEqualTo("2.4.0");
        assertThat(doc.serverUrl()).isEqualTo("https://orders.example.com/v2");
        assertThat(doc.operations()).extracting(OpenApiDocument.Operation::operationId)
                .containsExactly("listOrders", "createOrder", "getOrder");
        assertThat(doc.operations().get(0).method()).isEqualTo("GET");
    }

    @Test
    void readsSwagger2Json() {
        OpenApiDocument doc = parser.parse("""
                {"swagger":"2.0","info":{"title":"Legacy","version":"1"},
                 "host":"legacy.example.com","basePath":"/api","schemes":["http"],
                 "paths":{"/ping":{"get":{"operationId":"ping"}}}}
                """);

        assertThat(doc.specVersion()).isEqualTo("2.0");
        assertThat(doc.serverUrl()).isEqualTo("http://legacy.example.com/api");
        assertThat(doc.operations()).hasSize(1);
    }

    @Test
    void serverUrlIsNullWhenNoneIsAbsolute() {
        OpenApiDocument doc = parser.parse("openapi: 3.0.0\ninfo: {title: X, version: '1'}\n"
                + "servers: [{url: 'https://{env}.example.com'}]\npaths: {}\n");
        assertThat(doc.serverUrl()).isNull();
        assertThat(doc.operations()).isEmpty();
    }

    @Test
    void rejectsNonOpenApiDocuments() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> parser.parse("- just\n- a list\n")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> parser.parse("openapi: 2.0\ninfo: {title: X}\n"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> parser.parse("openapi: 3.0.0\ninfo: {version: '1'}\n"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("info.title");
        assertThatThrownBy(() -> parser.parse("openapi: [broken")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsOversizedSpecs() {
        String huge = "openapi: 3.0.0\ninfo: {title: X}\n# " + "x".repeat(OpenApiParser.MAX_SPEC_BYTES);
        assertThatThrownBy(() -> parser.parse(huge)).hasMessageContaining("2 MB");
    }
}

package com.portica.management.mcp;

import com.portica.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiToolsTest {

    @Test
    void oneToolPerOperationWithParametersAndBody() {
        List<OpenApiTools.Tool> tools = OpenApiTools.generate("""
                openapi: 3.0.3
                info: {title: Orders, version: '1'}
                paths:
                  /orders/{orderId}:
                    parameters:
                      - {name: orderId, in: path, required: true, schema: {type: string}}
                    get:
                      operationId: get-order
                      summary: Get an order
                      parameters:
                        - {name: expand, in: query, schema: {type: array, items: {type: string}}}
                        - {name: X-Tenant, in: header, required: true, schema: {type: string}}
                        - {name: session, in: cookie, schema: {type: string}}
                      responses: {'200': {description: ok}}
                  /orders:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Order'}
                      responses: {'201': {description: created}}
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        item: {type: string}
                        parent: {$ref: '#/components/schemas/Order'}
                """);
        assertThat(tools).extracting(OpenApiTools.Tool::name).containsExactly("get-order", "post_orders");

        OpenApiTools.Tool get = tools.getFirst();
        assertThat(get.title()).isEqualTo("Get an order");
        assertThat(get.readOnly()).isTrue();
        assertThat(get.inputSchema().get("required")).isEqualTo(List.of("orderId", "X-Tenant"));
        assertThat(get.http().parameters()).extracting(OpenApiTools.HttpParameter::in)
                .containsExactlyInAnyOrder("path", "query", "header");   // cookies aren't mapped
        assertThat(get.http().path()).isEqualTo("/orders/{orderId}");

        OpenApiTools.Tool post = tools.get(1);
        assertThat(post.readOnly()).isFalse();
        assertThat(post.http().hasBody()).isTrue();
        assertThat(post.http().bodyContentType()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) post.inputSchema().get("properties")).get("body");
        assertThat(body.get("type")).isEqualTo("object");
        // the recursive reference is cut short instead of looping
        assertThat(body.toString()).contains("item");
    }

    @Test
    void swagger2BodyParametersAndUniqueNames() {
        List<OpenApiTools.Tool> tools = OpenApiTools.generate("""
                swagger: '2.0'
                info: {title: Legacy, version: '1'}
                paths:
                  /a:
                    put:
                      operationId: save
                      parameters:
                        - {name: payload, in: body, required: true, schema: {type: object}}
                      responses: {'200': {description: ok}}
                  /b:
                    put:
                      operationId: save
                      parameters:
                        - {name: limit, in: query, type: integer, maximum: 50}
                      responses: {'200': {description: ok}}
                """);
        assertThat(tools).extracting(OpenApiTools.Tool::name).containsExactly("save", "save_2");
        assertThat(tools.getFirst().inputSchema().get("required")).isEqualTo(List.of("body"));
        assertThat(tools.get(1).inputSchema().toString()).contains("maximum=50");
    }

    @Test
    void namesAreSafeForMcpClients() {
        assertThat(OpenApiTools.toolName(null, "get", "/users/{id}/posts")).isEqualTo("get_users_id_posts");
        assertThat(OpenApiTools.toolName("pets.list (v2)", "get", "/x")).isEqualTo("pets_list_v2");
        assertThat(OpenApiTools.toolName("a".repeat(100), "get", "/x")).hasSize(64);
    }

    @Test
    void rejectsDocumentsThatArentOpenApi() {
        assertThatThrownBy(() -> OpenApiTools.generate("just: [unclosed")).isInstanceOf(InvalidRequestException.class);
    }
}

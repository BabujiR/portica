package com.portica.common.policy.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplateTest {

    private final Map<String, Object> vars = Map.of("request.header.name", "Ada", "id", 42);

    @Test
    void substitutesVariablesAndLeavesUnknownEmpty() {
        assertThat(MessageTemplate.of("Hello {request.header.name}{missing}!").render(vars::get)).isEqualTo("Hello Ada!");
    }

    @Test
    void jsonBracesStayLiteral() {
        MessageTemplate t = MessageTemplate.of("{\"id\":{id},\"name\":\"{request.header.name}\"}");
        assertThat(t.render(vars::get)).isEqualTo("{\"id\":42,\"name\":\"Ada\"}");
        assertThat(t.variables()).containsExactly("id", "request.header.name");
    }

    @Test
    void constantTemplates() {
        assertThat(MessageTemplate.of("plain").isConstant()).isTrue();
        assertThat(MessageTemplate.of(null).render(vars::get)).isEmpty();
        assertThat(MessageTemplate.of("{request.content}").references("request.content")).isTrue();
    }
}

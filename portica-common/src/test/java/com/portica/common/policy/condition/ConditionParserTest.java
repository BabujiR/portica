package com.portica.common.policy.condition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionParserTest {

    private static final Map<String, Object> VARS = Map.of(
            "request.verb", "GET",
            "proxy.pathsuffix", "/pets/42/photos",
            "request.header.x-debug", "true",
            "response.status.code", 503,
            "client.ip", "10.1.2.3",
            "empty", "",
            "zero", "0",
            "flag", Boolean.FALSE);

    private static boolean eval(String condition) {
        return ConditionParser.parse(condition).test(VARS::get);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiterString = " => ", textBlock = """
            request.verb == "GET" => true
            request.verb != "GET" => false
            request.verb == "get" => false
            proxy.pathsuffix ~ "/pets/*" => false
            proxy.pathsuffix ~ "/pets/*/photos" => true
            proxy.pathsuffix ~ "/pets/**" => true
            proxy.pathsuffix ~~ "/pets/[0-9]+/.*" => true
            client.ip =| "10." => true
            response.status.code >= 500 => true
            response.status.code < 500 => false
            response.status.code == 503 => true
            response.status.code == "503" => true
            request.header.x-debug => true
            missing.variable => false
            empty => false
            zero => false
            flag => false
            flag == false => true
            not request.header.x-debug => false
            !(request.verb == "POST") => true
            request.verb == "POST" or request.verb == "GET" => true
            request.verb == "GET" AND response.status.code > 500 => true
            request.verb == "GET" && (empty || zero) => false
            missing.variable == null => true
            missing.variable != "x" => true
            missing.variable ~ "*" => false
            """)
    void evaluates(String condition, boolean expected) {
        assertThat(eval(condition)).isEqualTo(expected);
    }

    @Test
    void blankMeansAlways() {
        assertThat(ConditionParser.parse(null)).isSameAs(Condition.ALWAYS);
        assertThat(ConditionParser.parse("  ").test(VariableResolver.EMPTY)).isTrue();
    }

    @Test
    void reportsReferencedVariables() {
        assertThat(ConditionParser.parse("request.content ~~ \"x\" and request.verb == \"POST\"").variables())
                .containsExactlyInAnyOrder("request.content", "request.verb");
    }

    @Test
    void escapedQuotesInStrings() {
        assertThat(ConditionParser.parse("a == \"say \\\"hi\\\"\"").test(Map.of("a", "say \"hi\"")::get)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"request.verb ==", "(request.verb == \"GET\"", "request.verb == \"GET", "a == 'single'",
            "a b", "a ~~ \"[unclosed\"", "== \"x\"", "a and", "a == 1.2.3"})
    void rejectsInvalidSyntax(String condition) {
        assertThatThrownBy(() -> ConditionParser.parse(condition)).isInstanceOf(ConditionSyntaxException.class);
    }

    @Test
    void evaluationErrorsAreFalseNotExceptions() {
        Condition c = ConditionParser.parse("a ~~ b");
        assertThat(c.test(Map.<String, Object>of("a", "x", "b", "[bad")::get)).isFalse();
    }
}

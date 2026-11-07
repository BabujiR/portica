package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.AssignMessageConfig;
import com.portica.common.policy.config.CorsConfig;
import com.portica.common.policy.config.JsonToXmlConfig;
import com.portica.common.policy.config.RaiseFaultConfig;
import com.portica.common.policy.config.XmlToJsonConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static com.portica.gateway.policy.TestMessages.text;
import static com.portica.gateway.policy.TestMessages.withResponse;
import static org.assertj.core.api.Assertions.assertThat;

class MediationPoliciesTest {

    @Nested
    class Cors {

        private final CorsPolicy cors = new CorsPolicy("cors", new CorsConfig(List.of("https://app.example.com"),
                List.of("GET", "POST"), List.of("*"), List.of("X-Total"), 600L, true));

        @Test
        void answersPreflightWithoutCallingTheTarget() {
            MessageContext ctx = request("OPTIONS", "/1").header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type, x-api-key").build();

            assertThat(run(cors, ctx)).isNull();
            assertThat(ctx.isShortCircuited()).isTrue();
            assertThat(ctx.response().status()).isEqualTo(204);
            assertThat(ctx.response().headers().getFirst("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
            assertThat(ctx.response().headers().getFirst("Access-Control-Allow-Headers")).isEqualTo("content-type, x-api-key");
            assertThat(ctx.response().headers().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(ctx.response().headers().getFirst("Access-Control-Max-Age")).isEqualTo("600");
        }

        @Test
        void rejectsPreflightFromUnknownOriginOrForbiddenMethod() {
            MessageContext badOrigin = request("OPTIONS", "/").header("Origin", "https://evil.example.com")
                    .header("Access-Control-Request-Method", "GET").build();
            run(cors, badOrigin);
            assertThat(badOrigin.response().status()).isEqualTo(403);

            MessageContext badMethod = request("OPTIONS", "/").header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "DELETE").build();
            run(cors, badMethod);
            assertThat(badMethod.response().status()).isEqualTo(403);
        }

        @Test
        void actualRequestsGetHeadersQueuedForTheResponse() {
            MessageContext ctx = request("GET", "/").header("Origin", "https://app.example.com").build();
            run(cors, ctx);
            assertThat(ctx.isShortCircuited()).isFalse();
            assertThat(ctx.pendingResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
            assertThat(ctx.pendingResponseHeaders().getFirst("Access-Control-Expose-Headers")).isEqualTo("X-Total");
            assertThat(ctx.pendingResponseHeaders().getFirst("Vary")).isEqualTo("Origin");

            MessageContext noOrigin = request("GET", "/").build();
            run(cors, noOrigin);
            assertThat(noOrigin.pendingResponseHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        void wildcardWithoutCredentialsSendsStar() {
            CorsPolicy open = new CorsPolicy("cors", new CorsConfig(List.of("*"), null, null, null, null, null));
            MessageContext ctx = request("GET", "/").header("Origin", "https://any.example.com").build();
            run(open, ctx);
            assertThat(ctx.pendingResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("*");
            assertThat(open.requiresBufferedResponse(PolicyPhase.RESPONSE)).isFalse();
        }
    }

    @Nested
    class AssignMessage {

        @Test
        void rewritesTheRequest() {
            AssignMessagePolicy policy = new AssignMessagePolicy("am", new AssignMessageConfig(null,
                    Map.of("X-User", "{request.header.x-name}"), Map.of("X-Trace", "t1"), List.of("x-api-key"),
                    Map.of("limit", "10"), List.of("debug"), "POST", "/v2/items",
                    "{\"name\":\"{request.header.x-name}\"}", "application/json", null, Map.of("my.var", "{request.verb}")));
            MessageContext ctx = request("GET", "/old?debug=1&q=a%20b").header("x-name", "Ada").header("x-api-key", "k").build();

            assertThat(run(policy, ctx)).isNull();
            assertThat(ctx.request().headers().getFirst("X-User")).isEqualTo("Ada");
            assertThat(ctx.request().headers().getFirst("x-api-key")).isNull();
            assertThat(ctx.request().method()).isEqualTo(HttpMethod.POST);
            assertThat(ctx.request().pathSuffix()).isEqualTo("/v2/items");
            assertThat(ctx.request().forwardedRawQuery()).isEqualTo("q=a%20b&limit=10");
            assertThat(text(ctx.request().body())).isEqualTo("{\"name\":\"Ada\"}");
            assertThat(ctx.variable("my.var")).isEqualTo("GET");
        }

        @Test
        void changesTheResponseAndOnlyBuffersWhenTheBodyIsTouched() {
            AssignMessagePolicy headersOnly = new AssignMessagePolicy("h", new AssignMessageConfig("response",
                    Map.of("X-Served-By", "portica"), null, List.of("Server"), null, null, null, null, null, null, 202, null));
            AssignMessagePolicy body = new AssignMessagePolicy("b", new AssignMessageConfig("response",
                    null, null, null, null, null, null, null, "wrapped:{response.content}", "text/plain", null, null));

            MessageContext ctx = withResponse(request("GET", "/").build(), 200, "text/plain", "hello");
            ctx.response().headers().set("Server", "backend");
            run(headersOnly, ctx);
            run(body, ctx);

            assertThat(ctx.response().status()).isEqualTo(202);
            assertThat(ctx.response().headers().getFirst("X-Served-By")).isEqualTo("portica");
            assertThat(ctx.response().headers().getFirst("Server")).isNull();
            assertThat(text(ctx.response().body())).isEqualTo("wrapped:hello");
            assertThat(headersOnly.requiresBufferedResponse(PolicyPhase.RESPONSE)).isFalse();
            assertThat(body.requiresBufferedResponse(PolicyPhase.RESPONSE)).isTrue();
        }

        @Test
        void responseHeadersSetDuringTheRequestFlowAreQueued() {
            AssignMessagePolicy policy = new AssignMessagePolicy("h", new AssignMessageConfig("response",
                    Map.of("X-Early", "yes"), null, null, null, null, null, null, null, null, null, null));
            MessageContext ctx = request("GET", "/").build();
            run(policy, ctx);
            assertThat(ctx.pendingResponseHeaders().getFirst("X-Early")).isEqualTo("yes");
        }
    }

    @Nested
    class RaiseFault {

        private final RaiseFaultPolicy raise = new RaiseFaultPolicy("blocked", new RaiseFaultConfig(451,
                Map.of("X-Reason", "{request.header.x-country}"), "{\"error\":\"unavailable\"}", null, "GeoBlocked"));

        @Test
        void raisesAFaultWithACustomResponse() {
            PolicyFault fault = run(raise, request("GET", "/").header("x-country", "XX").build());
            assertThat(fault.faultName()).isEqualTo("GeoBlocked");
            assertThat(fault.status()).isEqualTo(451);
            assertThat(fault.headers().getFirst("X-Reason")).isEqualTo("XX");
            assertThat(text(fault.body())).isEqualTo("{\"error\":\"unavailable\"}");
        }

        @Test
        void insideAFaultRuleItReplacesTheErrorResponse() {
            MessageContext ctx = request("GET", "/").build();
            ctx.enterFault(PolicyFault.of("InvalidApiKey", 401, "bad key"));
            assertThat(run(raise, ctx)).isNull();
            assertThat(ctx.response().status()).isEqualTo(451);
            assertThat(text(ctx.response().body())).isEqualTo("{\"error\":\"unavailable\"}");
        }
    }

    @Nested
    class Transformations {

        @Test
        void jsonToXmlOnTheResponse() {
            JsonToXmlPolicy policy = new JsonToXmlPolicy("j2x", new JsonToXmlConfig(null, "pets", null));
            MessageContext ctx = withResponse(request("GET", "/").build(), 200, "application/json",
                    "{\"pet\":{\"id\":1,\"tags\":[\"a\",\"b\"],\"name\":\"R&B\"}}");

            assertThat(run(policy, ctx)).isNull();
            assertThat(text(ctx.response().body())).contains("<pet><id>1</id><tags>a</tags><tags>b</tags><name>R&amp;B</name></pet>");
            assertThat(ctx.response().headers().getFirst("Content-Type")).startsWith("application/xml");
        }

        @Test
        void xmlToJsonOnTheRequest() {
            XmlToJsonPolicy policy = new XmlToJsonPolicy("x2j", new XmlToJsonConfig("request", true, true));
            MessageContext ctx = request("POST", "/").header("Content-Type", "application/xml")
                    .body("<order id=\"7\"><item>a</item><item>b</item><qty>2</qty><gift>true</gift></order>").build();

            assertThat(run(policy, ctx)).isNull();
            assertThat(text(ctx.request().body()))
                    .isEqualTo("{\"order\":{\"@id\":7,\"item\":[\"a\",\"b\"],\"qty\":2,\"gift\":true}}");
            assertThat(policy.requiresRequestBody()).isTrue();
        }

        @Test
        void invalidBodiesFaultAndOtherContentTypesPassThrough() {
            XmlToJsonPolicy x2j = new XmlToJsonPolicy("x2j", new XmlToJsonConfig("request", null, null));
            MessageContext bad = request("POST", "/").header("Content-Type", "text/xml").body("<open>").build();
            assertThat(run(x2j, bad).faultName()).isEqualTo("InvalidXML");

            MessageContext json = request("POST", "/").header("Content-Type", "application/json").body("{}").build();
            assertThat(run(x2j, json)).isNull();
            assertThat(text(json.request().body())).isEqualTo("{}");
        }

        @Test
        void xmlParsingRejectsExternalEntities() {
            XmlToJsonPolicy x2j = new XmlToJsonPolicy("x2j", new XmlToJsonConfig("request", null, null));
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";
            MessageContext ctx = request("POST", "/").header("Content-Type", "application/xml").body(xxe).build();
            run(x2j, ctx); // either rejected or converted without resolving the entity
            assertThat(text(ctx.request().body())).doesNotContain("root:");
        }
    }
}

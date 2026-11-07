package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.ServiceCalloutConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static org.assertj.core.api.Assertions.assertThat;

/** ServiceCallout against a local JDK HTTP server. FlowCallout is covered in ProxyBundleCompilerTest. */
class ExtensionPoliciesTest {

    private static HttpServer server;
    private static String base;
    private final WebClient client = WebClient.create();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lookup", exchange -> {
            String user = exchange.getRequestHeaders().getFirst("X-User");
            byte[] body = ("{\"user\":\"" + user + "\",\"tier\":\"gold\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Lookup", "done");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void callsTheServiceAndExposesTheResponseAsVariables() {
        ServiceCalloutPolicy policy = new ServiceCalloutPolicy("lookup", new ServiceCalloutConfig(
                base + "/lookup?id={request.queryparam.id}", null, Map.of("X-User", "{request.header.x-name}"),
                null, 2_000L, null), client);
        MessageContext ctx = request("GET", "/?id=9").header("x-name", "ada").build();

        assertThat(run(policy, ctx)).isNull();
        assertThat(ctx.variable("servicecallout.lookup.response.status.code")).isEqualTo(200);
        assertThat(ctx.variable("servicecallout.lookup.response.content")).isEqualTo("{\"user\":\"ada\",\"tier\":\"gold\"}");
        assertThat(ctx.variable("servicecallout.lookup.response.header.x-lookup")).isEqualTo("done");
    }

    @Test
    void errorsFaultOnlyWhenFailOnErrorIsSet() {
        ServiceCalloutPolicy strict = new ServiceCalloutPolicy("s",
                new ServiceCalloutConfig(base + "/broken", null, null, null, 2_000L, true), client);
        ServiceCalloutPolicy lenient = new ServiceCalloutPolicy("l",
                new ServiceCalloutConfig(base + "/broken", null, null, null, 2_000L, false), client);

        PolicyFault fault = run(strict, request("GET", "/").build());
        assertThat(fault.faultName()).isEqualTo("ServiceCalloutFailed");
        assertThat(fault.status()).isEqualTo(502);

        MessageContext ctx = request("GET", "/").build();
        assertThat(run(lenient, ctx)).isNull();
        assertThat(ctx.variable("servicecallout.l.failed")).isEqualTo(true);
        assertThat(ctx.variable("servicecallout.l.response.status.code")).isEqualTo(503);
    }

    @Test
    void refusesNonHttpUrlsAndUnreachableHosts() {
        ServiceCalloutPolicy file = new ServiceCalloutPolicy("f",
                new ServiceCalloutConfig("{request.header.x-target}", null, null, null, 500L, true), client);
        assertThat(run(file, request("GET", "/").header("x-target", "file:///etc/passwd").build())).isNotNull();

        ServiceCalloutPolicy down = new ServiceCalloutPolicy("d",
                new ServiceCalloutConfig("http://127.0.0.1:1/x", null, null, null, 500L, true), client);
        assertThat(run(down, request("GET", "/").build()).faultName()).isEqualTo("ServiceCalloutFailed");
    }
}

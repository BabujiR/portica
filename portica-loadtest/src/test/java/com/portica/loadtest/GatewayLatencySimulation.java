package com.portica.loadtest;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Checks the gateway's latency targets: an open workload of {@code rps} requests per second through a
 * proxy that verifies an API key, applies the product quota and SpikeArrest (Redis) and forwards to a fast
 * backend, next to the same requests sent straight to the backend as a baseline. The build fails when the
 * gateway's p95 / p99 response time or its success rate miss their targets.
 * <p>
 * Settings (system properties): {@code gatewayUrl} (http://localhost:8081), {@code path}
 * (/acme/dev/echo/get), {@code apiKey}, {@code backendUrl} (http://localhost:8090/get; empty skips the
 * baseline), {@code rps} (200), {@code duration} seconds (60), {@code rampSeconds} (15), {@code p95Ms} (25),
 * {@code p99Ms} (50), {@code minSuccessPercent} (99.9).
 */
public class GatewayLatencySimulation extends Simulation {

    private static final String GATEWAY = System.getProperty("gatewayUrl", "http://localhost:8081");
    private static final String PATH = System.getProperty("path", "/acme/dev/echo/get");
    private static final String API_KEY = System.getProperty("apiKey", "");
    private static final String BACKEND = System.getProperty("backendUrl", "http://localhost:8090/get");
    private static final double RPS = Double.parseDouble(System.getProperty("rps", "200"));
    private static final int DURATION = Integer.getInteger("duration", 60);
    private static final int RAMP = Integer.getInteger("rampSeconds", 15);
    private static final int P95_MS = Integer.getInteger("p95Ms", 25);
    private static final int P99_MS = Integer.getInteger("p99Ms", 50);
    private static final double MIN_SUCCESS = Double.parseDouble(System.getProperty("minSuccessPercent", "99.9"));

    private final HttpProtocolBuilder protocol = http
            .acceptHeader("application/json")
            .userAgentHeader("portica-loadtest")
            .shareConnections()          // like a client pool: measure the gateway, not TCP handshakes
            .disableCaching();

    private final ScenarioBuilder viaGateway = scenario("via gateway")
            .exec(http("gateway").get(GATEWAY + PATH)
                    .header("x-api-key", API_KEY)
                    .check(status().is(200)));

    private final ScenarioBuilder direct = scenario("direct to backend")
            .exec(http("backend").get(BACKEND).check(status().is(200)));

    {
        var gatewayLoad = viaGateway.injectOpen(
                rampUsersPerSec(1).to(RPS).during(Duration.ofSeconds(RAMP)),
                constantUsersPerSec(RPS).during(Duration.ofSeconds(DURATION)));
        if (BACKEND.isBlank()) {
            setUp(gatewayLoad)
                    .protocols(protocol)
                    .assertions(assertions());
        } else {
            setUp(gatewayLoad,
                    direct.injectOpen(
                            rampUsersPerSec(1).to(RPS / 10).during(Duration.ofSeconds(RAMP)),
                            constantUsersPerSec(RPS / 10).during(Duration.ofSeconds(DURATION))))
                    .protocols(protocol)
                    .assertions(assertions());
        }
    }

    private static io.gatling.javaapi.core.Assertion[] assertions() {
        return new io.gatling.javaapi.core.Assertion[]{
                details("gateway").responseTime().percentile(95.0).lt(P95_MS),
                details("gateway").responseTime().percentile(99.0).lt(P99_MS),
                details("gateway").successfulRequests().percent().gte(MIN_SUCCESS),
                global().failedRequests().count().lt((long) Math.ceil(RPS * DURATION * (100 - MIN_SUCCESS) / 100) + 1)
        };
    }
}

package com.portica.gateway.policy.engine;

import com.portica.common.policy.ConditionalFlow;
import com.portica.common.policy.FaultRule;
import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.impl.AssignMessagePolicy;
import com.portica.gateway.policy.impl.FlowCalloutPolicy;
import com.portica.gateway.policy.impl.JsonToXmlPolicy;
import com.portica.gateway.policy.impl.RaiseFaultPolicy;
import com.portica.gateway.policy.impl.XmlToJsonPolicy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeSharedFlow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static org.assertj.core.api.Assertions.assertThat;

class ProxyBundleCompilerTest {

    private final List<PolicyFactory> factories = List.of(new AssignMessagePolicy.Factory(),
            new FlowCalloutPolicy.Factory(), new JsonToXmlPolicy.Factory(), new XmlToJsonPolicy.Factory(),
            new RaiseFaultPolicy.Factory());
    private final ProxyBundleCompiler compiler = new ProxyBundleCompiler(factories);

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, config);
    }

    private static List<FlowStep> steps(String... names) {
        return java.util.Arrays.stream(names).map(n -> new FlowStep(n, null)).toList();
    }

    private static RuntimeProxy proxy(String name, String basePath, List<PolicyDefinition> policies, ProxyFlows flows) {
        return new RuntimeProxy(name, 1, basePath, "http://backend:8080", "", 1000, 5000, policies, flows);
    }

    private CompiledEnvironment compile(List<RuntimeProxy> proxies, List<RuntimeSharedFlow> sharedFlows) {
        return compiler.compile(new RuntimeEnvironmentConfig("acme", "dev", 1, Instant.now(), proxies, sharedFlows));
    }

    private static final PolicyDefinition HEADER = policy("add-header", "AssignMessage",
            Map.of("target", "response", "setHeaders", Map.of("X-Served-By", "portica")));
    private static final PolicyDefinition TO_XML = policy("to-xml", "JSONToXML", Map.of());
    private static final PolicyDefinition FROM_XML = policy("from-xml", "XMLToJSON", Map.of("target", "request"));

    @Test
    void headerOnlyResponsePoliciesKeepTheResponseStreaming() {
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(HEADER),
                new ProxyFlows(null, null, new FlowPair(null, steps("add-header")), null, null))), List.of())
                .proxies().get("acme/dev/pets@1");

        assertThat(p.postResponse()).hasSize(1);
        assertThat(p.bufferedResponse()).isFalse();
        assertThat(p.requiresRequestBody()).isFalse();
        assertThat(p.hasNoSteps()).isFalse();
    }

    @Test
    void bodyPoliciesTurnOnBuffering() {
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(TO_XML, FROM_XML),
                new ProxyFlows(new FlowPair(steps("from-xml"), steps("to-xml")), null, null, null, null))), List.of())
                .proxies().get("acme/dev/pets@1");

        assertThat(p.bufferedResponse()).isTrue();
        assertThat(p.requiresRequestBody()).isTrue();
    }

    @Test
    void conditionsReadingContentTurnOnBuffering() {
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(HEADER),
                new ProxyFlows(new FlowPair(List.of(new FlowStep("add-header", "request.content ~~ \".*x.*\"")), null),
                        null, null, null, null))), List.of()).proxies().get("acme/dev/pets@1");
        assertThat(p.requiresRequestBody()).isTrue();
    }

    @Test
    void disabledPoliciesAreDroppedFromFlows() {
        PolicyDefinition disabled = new PolicyDefinition("to-xml", "JSONToXML", false, null, Map.of());
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(disabled),
                new ProxyFlows(new FlowPair(null, steps("to-xml")), null, null, null, null))), List.of())
                .proxies().get("acme/dev/pets@1");

        assertThat(p.preResponse()).isEmpty();
        assertThat(p.bufferedResponse()).isFalse();
        assertThat(p.hasNoSteps()).isTrue();
    }

    @Test
    void firstMatchingConditionalFlowIsSelected() {
        ProxyFlows flows = new ProxyFlows(null, List.of(
                new ConditionalFlow("get-one", "request.verb == \"GET\" and proxy.pathsuffix ~ \"/*\"", steps("add-header"), null),
                new ConditionalFlow("any", null, null, null)), null, null, null);
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(HEADER), flows)), List.of())
                .proxies().get("acme/dev/pets@1");

        assertThat(p.match(request("GET", "/7").build().resolver()).name()).isEqualTo("get-one");
        assertThat(p.match(request("POST", "/7").build().resolver()).name()).isEqualTo("any");
    }

    @Test
    void faultRulesAreCompiled() {
        PolicyDefinition raise = policy("custom-401", "RaiseFault", Map.of("statusCode", 401, "body", "{\"error\":\"no\"}"));
        ProxyFlows flows = new ProxyFlows(null, null, null,
                List.of(new FaultRule("bad-key", "fault.name == \"InvalidApiKey\"", steps("custom-401"))),
                new FaultRule("default", null, steps("custom-401")));
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(raise), flows)), List.of())
                .proxies().get("acme/dev/pets@1");

        assertThat(p.faultRules()).hasSize(1);
        assertThat(p.defaultFaultRule().steps()).hasSize(1);
        assertThat(p.hasNoSteps()).isFalse();
    }

    @Test
    void flowCalloutRunsTheSharedFlowAndInheritsItsBufferingNeeds() {
        RuntimeSharedFlow common = new RuntimeSharedFlow("common", 3,
                List.of(policy("tag", "AssignMessage", Map.of("setHeaders", Map.of("X-Shared", "{request.verb}")))),
                steps("tag"));
        RuntimeSharedFlow xml = new RuntimeSharedFlow("xml", 1, List.of(TO_XML), steps("to-xml"));
        PolicyDefinition callCommon = policy("call-common", "FlowCallout", Map.of("sharedFlow", "common"));
        PolicyDefinition callXml = policy("call-xml", "FlowCallout", Map.of("sharedFlow", "xml"));
        CompiledProxy p = compile(List.of(proxy("pets", "/pets", List.of(callCommon, callXml),
                        new ProxyFlows(new FlowPair(steps("call-common"), steps("call-xml")), null, null, null, null))),
                List.of(common, xml)).proxies().get("acme/dev/pets@1");

        MessageContext ctx = request("PUT", "/1").build();
        FlowExecutor.run(p.preRequest(), ctx).block();
        assertThat(ctx.request().headers().getFirst("X-Shared")).isEqualTo("PUT");
        assertThat(p.bufferedResponse()).isTrue();
    }

    @Test
    void brokenProxiesFailClosedWithoutAffectingOthers() {
        PolicyDefinition toMissing = policy("call", "FlowCallout", Map.of("sharedFlow", "missing"));
        PolicyDefinition toA = policy("call-a", "FlowCallout", Map.of("sharedFlow", "a"));
        RuntimeSharedFlow a = new RuntimeSharedFlow("a", 1,
                List.of(policy("call-b", "FlowCallout", Map.of("sharedFlow", "b"))), steps("call-b"));
        RuntimeSharedFlow b = new RuntimeSharedFlow("b", 1,
                List.of(policy("call-a", "FlowCallout", Map.of("sharedFlow", "a"))), steps("call-a"));
        PolicyDefinition badConfig = policy("bad", "AssignMessage", Map.of("nonsense", true));

        CompiledEnvironment env = compile(List.of(
                proxy("missing-flow", "/m", List.of(toMissing), new ProxyFlows(new FlowPair(steps("call"), null), null, null, null, null)),
                proxy("cycle", "/c", List.of(toA), new ProxyFlows(new FlowPair(steps("call-a"), null), null, null, null, null)),
                proxy("bad-config", "/b", List.of(badConfig), ProxyFlows.EMPTY),
                proxy("healthy", "/h", List.of(HEADER), ProxyFlows.EMPTY)), List.of(a, b));

        assertThat(env.proxies()).containsOnlyKeys("acme/dev/healthy@1");
    }

    @Test
    void policyTypesWithoutAFactoryFailClosed() {
        ProxyBundleCompiler limited = new ProxyBundleCompiler(List.of(new AssignMessagePolicy.Factory()));
        CompiledEnvironment env = limited.compile(new RuntimeEnvironmentConfig("acme", "dev", 1, Instant.now(),
                List.of(proxy("pets", "/pets", List.of(TO_XML), ProxyFlows.EMPTY))));
        assertThat(env.proxies()).isEmpty();
    }
}

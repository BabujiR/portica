package com.portica.gateway.routing;

import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDefinitionFactoryTest {

    private final RuntimeProxy petstore = new RuntimeProxy("petstore", 3, "/petstore",
            "https://petstore.example.com:443", "/v2", 2_000, 15_000);
    private final RuntimeProxy petstoreAdmin = new RuntimeProxy("petstore-admin", 1, "/petstore/admin",
            "http://admin.internal:8080", "", 5_000, 30_000);

    @Test
    void buildsOneRoutePerDeployedProxy() {
        List<RouteDefinition> routes = RouteDefinitionFactory.build(List.of(
                new RuntimeEnvironmentConfig("acme", "dev", 7, Instant.now(), List.of(petstore, petstoreAdmin)),
                new RuntimeEnvironmentConfig("acme", "prod", 2, Instant.now(), List.of(petstore))));

        assertThat(routes).extracting(RouteDefinition::getId).containsExactlyInAnyOrder(
                "portica_acme_dev_petstore", "portica_acme_dev_petstore-admin", "portica_acme_prod_petstore");
    }

    @Test
    void routeMatchesOrgEnvAndBasePathAndCarriesTargetAndTimeouts() {
        RouteDefinition route = RouteDefinitionFactory.route("acme", "dev", petstore);

        assertThat(route.getUri()).isEqualTo(URI.create("https://petstore.example.com:443"));
        assertThat(route.getPredicates()).hasSize(1);
        assertThat(route.getPredicates().get(0).getName()).isEqualTo("Path");
        assertThat(route.getPredicates().get(0).getArgs().values()).containsExactly("/acme/dev/petstore/**");

        assertThat(route.getFilters()).hasSize(1);
        assertThat(route.getFilters().get(0).getName()).isEqualTo("PorticaProxy");
        assertThat(route.getFilters().get(0).getArgs())
                .containsEntry("prefix", "/acme/dev/petstore")
                .containsEntry("targetPath", "/v2")
                .containsEntry("proxy", "petstore")
                .containsEntry("revision", "3");

        assertThat(route.getMetadata())
                .containsEntry("connect-timeout", 2_000)
                .containsEntry("response-timeout", 15_000L)
                .containsEntry(RouteDefinitionFactory.METADATA_REVISION, 3);
    }

    @Test
    void longerBasePathsHaveHigherPriority() {
        RouteDefinition general = RouteDefinitionFactory.route("acme", "dev", petstore);
        RouteDefinition specific = RouteDefinitionFactory.route("acme", "dev", petstoreAdmin);

        assertThat(specific.getOrder()).isLessThan(general.getOrder()); // lower order = evaluated first
    }
}

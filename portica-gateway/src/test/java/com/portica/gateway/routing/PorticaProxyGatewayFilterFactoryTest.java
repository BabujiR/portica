package com.portica.gateway.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PorticaProxyGatewayFilterFactoryTest {

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
            /acme/dev/pets/1        | /v2 | /v2/1
            /acme/dev/pets          | /v2 | /v2
            /acme/dev/pets/         | /v2 | /v2/
            /acme/dev/pets/1        | ''  | /1
            /acme/dev/pets          | ''  | /
            /acme/dev/pets/a%20b/c  | /v2 | /v2/a%20b/c
            """)
    void rewritesGatewayPathToTargetPath(String incoming, String targetPath, String expected) {
        assertThat(PorticaProxyGatewayFilterFactory.rewritePath(incoming, "/acme/dev/pets", targetPath))
                .isEqualTo(expected);
    }

    @Test
    void runsBeforeTheResponseWriterSoResponsePoliciesSeeTheTargetResponse() {
        GatewayFilter filter = new PorticaProxyGatewayFilterFactory(null, null)
                .apply(new PorticaProxyGatewayFilterFactory.Config());
        assertThat(filter).isInstanceOf(Ordered.class);
        assertThat(((Ordered) filter).getOrder()).isLessThan(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER);
    }
}

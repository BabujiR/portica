package com.portica.management.proxy;

import com.portica.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetSpecTest {

    @Test
    void urlTargetIsSplitIntoOriginAndPath() {
        TargetSpec spec = TargetSpec.of("HTTPS://api.example.com:8443/v2/", null, null);
        assertThat(spec.url()).isEqualTo("https://api.example.com:8443");
        assertThat(spec.path()).isEqualTo("/v2");
        assertThat(spec.usesServer()).isFalse();
        assertThat(spec.describe()).isEqualTo("https://api.example.com:8443/v2");
    }

    @Test
    void urlWithoutPathHasEmptyPath() {
        assertThat(TargetSpec.of("http://localhost:9000", null, null).path()).isEmpty();
        assertThat(TargetSpec.of("http://localhost:9000/", null, null).path()).isEmpty();
    }

    @Test
    void serverTargetNormalizesPath() {
        TargetSpec spec = TargetSpec.of(null, "orders-backend", "api/v1/");
        assertThat(spec.server()).isEqualTo("orders-backend");
        assertThat(spec.path()).isEqualTo("/api/v1");
        assertThat(spec.describe()).isEqualTo("server:orders-backend/api/v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x.example.com", "https://", "not a url", "https://u:p@x.example.com",
            "https://x.example.com/?a=1", "https://x.example.com/#frag", "//x.example.com"})
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> TargetSpec.of(url, null, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requiresExactlyOneTargetStyle() {
        assertThatThrownBy(() -> TargetSpec.of(null, null, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> TargetSpec.of("https://a.example.com", "srv", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> TargetSpec.of("https://a.example.com", null, "/v1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("only used with 'server'");
        assertThatThrownBy(() -> TargetSpec.of(null, "Bad_Name", null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> TargetSpec.of(null, "srv", "/has space")).isInstanceOf(InvalidRequestException.class);
    }
}

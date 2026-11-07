package com.portica.common.security;

import com.portica.common.http.PorticaPaths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void sha256HexMatchesTheKnownVector() {
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesComparesHashesSafely() {
        String h = Hashing.sha256Hex("secret");
        assertThat(Hashing.matches(h, Hashing.sha256Hex("secret"))).isTrue();
        assertThat(Hashing.matches(h, Hashing.sha256Hex("Secret"))).isFalse();
        assertThat(Hashing.matches(null, h)).isFalse();
    }

    @Test
    void oauthPathsAreReserved() {
        assertThat(PorticaPaths.isReserved("/oauth2")).isTrue();
        assertThat(PorticaPaths.isReserved("/OAuth2/token")).isTrue();
        assertThat(PorticaPaths.isReserved("/oauth2x")).isFalse();
        assertThat(PorticaPaths.isReserved("/pets")).isFalse();
    }
}

package com.portica.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrRangeTest {

    @Test
    void matchesIpv4Ranges() {
        CidrRange range = CidrRange.parse("10.0.0.0/8");
        assertThat(range.contains("10.255.1.2")).isTrue();
        assertThat(range.contains("11.0.0.1")).isFalse();
        assertThat(CidrRange.parse("192.168.1.10").contains("192.168.1.10")).isTrue();
        assertThat(CidrRange.parse("192.168.1.10").contains("192.168.1.11")).isFalse();
        assertThat(CidrRange.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
        assertThat(CidrRange.parse("172.16.0.0/12").contains("172.31.255.255")).isTrue();
        assertThat(CidrRange.parse("172.16.0.0/12").contains("172.32.0.0")).isFalse();
    }

    @Test
    void matchesIpv6Ranges() {
        CidrRange range = CidrRange.parse("2001:db8::/32");
        assertThat(range.contains("2001:db8:1::1")).isTrue();
        assertThat(range.contains("2001:db9::1")).isFalse();
        assertThat(range.contains("10.0.0.1")).isFalse();
        assertThat(CidrRange.parse("::1").contains("[::1]")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "example.com", "10.0.0.0/33", "256.1.1.1", "10.0.0.0/x", "::1/129"})
    void rejectsInvalidRangesWithoutDnsLookups(String text) {
        assertThatThrownBy(() -> CidrRange.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonLiteralsNeverMatch() {
        assertThat(CidrRange.parse("10.0.0.0/8").contains("localhost")).isFalse();
        assertThat(CidrRange.parse("10.0.0.0/8").contains((String) null)).isFalse();
    }
}

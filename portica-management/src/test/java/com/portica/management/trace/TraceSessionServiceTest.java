package com.portica.management.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSessionServiceTest {

    @Test
    void requestIdsAreQuotedAsClickHouseStringLiterals() {
        assertThat(TraceSessionService.quote("abc")).isEqualTo("'abc'");
        assertThat(TraceSessionService.quote("a'b\\c")).isEqualTo("'a\\'b\\\\c'");
    }
}

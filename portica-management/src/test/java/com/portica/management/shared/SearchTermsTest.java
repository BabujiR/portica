package com.portica.management.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTermsTest {

    @Test
    void absentTermMatchesEverything() {
        assertThat(SearchTerms.normalize(null)).isEmpty();
        assertThat(SearchTerms.normalize("   ")).isEmpty();
    }

    @Test
    void lowercasesTrimsAndEscapesWildcards() {
        assertThat(SearchTerms.normalize("  Acme ")).isEqualTo("acme");
        assertThat(SearchTerms.normalize("100%_off\\")).isEqualTo("100\\%\\_off\\\\");
    }

    @Test
    void capsLength() {
        assertThat(SearchTerms.normalize("a".repeat(500))).hasSize(100);
    }
}

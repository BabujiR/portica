package com.portica.common.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void computesTotalPages() {
        assertThat(PageResponse.of(List.of("a"), 0, 20, 0).totalPages()).isZero();
        assertThat(PageResponse.of(List.of("a"), 0, 20, 20).totalPages()).isEqualTo(1);
        assertThat(PageResponse.of(List.of("a"), 0, 20, 21).totalPages()).isEqualTo(2);
    }

    @Test
    void itemsAreDefensivelyCopied() {
        var source = new ArrayList<>(List.of("a", "b"));
        var page = PageResponse.of(source, 0, 10, 2);
        source.add("c");
        assertThat(page.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> page.items().add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidPaging() {
        assertThatThrownBy(() -> PageResponse.of(List.of(), -1, 10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageResponse.of(List.of(), 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

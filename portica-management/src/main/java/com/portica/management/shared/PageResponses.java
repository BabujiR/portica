package com.portica.management.shared;

import com.portica.common.api.PageResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public final class PageResponses {

    private PageResponses() {
    }

    public static <E, D> PageResponse<D> from(Page<E> page, Function<E, D> mapper) {
        List<D> items = page.getContent().stream().map(mapper).toList();
        return PageResponse.of(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <D> PageResponse<D> of(Page<?> page, List<D> items) {
        return PageResponse.of(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}

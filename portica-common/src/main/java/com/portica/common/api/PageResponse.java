package com.portica.common.api;

import java.util.List;
import java.util.Objects;

/**
 * Stable JSON envelope for paginated list endpoints. Pages are zero-based.
 *
 * @param items         items on this page
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total number of matching items
 * @param totalPages    total number of pages
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be >= 0");
        }
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1"); // checked before dividing by size
        }
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(items, page, size, totalElements, totalPages);
    }
}

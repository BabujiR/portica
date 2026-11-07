package com.portica.management.shared;

import com.portica.common.error.InvalidRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.TreeSet;

/**
 * Validates client-supplied sort properties against an allow-list before they reach JPQL,
 * so unknown or internal fields produce a clear 400 instead of a query error.
 */
public final class PageRequests {

    /** Sortable on every auditable resource. */
    public static final Set<String> STANDARD_SORTS = Set.of("name", "displayName", "createdAt", "updatedAt");

    private PageRequests() {
    }

    public static Pageable requireSortable(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidRequestException("Cannot sort by '%s'. Allowed: %s"
                        .formatted(order.getProperty(), String.join(", ", new TreeSet<>(allowed))));
            }
        }
        return pageable;
    }
}

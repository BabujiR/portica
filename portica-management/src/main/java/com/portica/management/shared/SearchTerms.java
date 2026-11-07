package com.portica.management.shared;

import java.util.Locale;

/**
 * Normalizes the optional {@code q} search parameter for repository {@code LIKE} queries.
 * An absent term becomes an empty string (matches everything), which avoids untyped
 * null parameters in JPQL; LIKE wildcards typed by users are escaped.
 */
public final class SearchTerms {

    public static final char ESCAPE = '\\';

    private SearchTerms() {
    }

    public static String normalize(String q) {
        if (q == null || q.isBlank()) {
            return "";
        }
        String trimmed = q.strip().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }
        return trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}

package com.portica.gateway.policy;

import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;

/** Header helpers that only use HttpHeaders methods stable across Spring Framework 6 and 7. */
public final class Headers {

    private Headers() {
    }

    public static HttpHeaders copy(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        if (source != null) {
            source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        }
        return copy;
    }

    /** Makes {@code target} contain exactly the headers of {@code source}. */
    public static void replaceAll(HttpHeaders target, HttpHeaders source) {
        List<String> names = new ArrayList<>();
        target.forEach((name, values) -> names.add(name));
        names.forEach(target::remove);
        source.forEach((name, values) -> target.put(name, new ArrayList<>(values)));
    }

    /** Adds every value of {@code extra} to {@code target} (set semantics for single-value CORS-style headers). */
    public static void setAll(HttpHeaders target, HttpHeaders extra) {
        extra.forEach((name, values) -> target.put(name, new ArrayList<>(values)));
    }

    /** Hop-by-hop and length headers that must not be copied blindly between messages. */
    public static void removeTransportHeaders(HttpHeaders headers) {
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.CONNECTION);
    }
}

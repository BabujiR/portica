package com.portica.common.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Null-tolerant immutable copies for model records (null elements are kept so validation can report them). */
final class Lists {

    private Lists() {
    }

    static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}

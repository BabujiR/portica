package com.portica.management.shared;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversions for simple JSONB columns (string lists and string maps) stored as text in entities. */
public final class JsonColumns {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {
    };

    private JsonColumns() {
    }

    public static String writeList(List<String> values) {
        return MAPPER.writeValueAsString(values == null ? List.of() : values);
    }

    public static List<String> readList(String json) {
        return json == null || json.isBlank() ? List.of() : List.copyOf(MAPPER.readValue(json, LIST));
    }

    public static String writeMap(Map<String, String> values) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((k, v) -> {
                if (k != null && v != null) {
                    clean.put(k, v);
                }
            });
        }
        return MAPPER.writeValueAsString(clean);
    }

    public static Map<String, String> readMap(String json) {
        return json == null || json.isBlank() ? Map.of() : java.util.Collections.unmodifiableMap(MAPPER.readValue(json, MAP));
    }
}

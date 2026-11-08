package com.portica.management.shared;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes records stored as JSONB "spec" columns. Unknown fields are ignored on read. */
public final class JsonSpecs {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonSpecs() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return json == null || json.isBlank() ? null : MAPPER.readValue(json, type);
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}

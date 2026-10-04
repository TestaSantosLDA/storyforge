package com.storyforge.stage1;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** snake_case JSON, matching the prompt schemas. */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    static String write(Object o) {
        return MAPPER.writeValueAsString(o);
    }

    static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}

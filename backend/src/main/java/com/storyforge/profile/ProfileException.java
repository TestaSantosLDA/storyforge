package com.storyforge.profile;

import java.util.Map;

/** A profile file, or a topic's overrides, that fails validation; one message per field. */
public class ProfileException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ProfileException(Map<String, String> fieldErrors) {
        super(describe(fieldErrors));
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public ProfileException(String message) {
        super(message);
        this.fieldErrors = Map.of();
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    private static String describe(Map<String, String> errors) {
        StringBuilder sb = new StringBuilder();
        errors.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "; ").append(k).append(": ").append(v));
        return sb.toString();
    }
}

package com.storyforge.validation;

import java.util.Map;

/** Input rejected with field-level messages; nothing was saved (docs/pipeline-a/stage-0 "Validation"). */
public class InvalidInputException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidInputException(Map<String, String> fieldErrors) {
        super("invalid input: " + fieldErrors);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public static InvalidInputException of(String field, String message) {
        return new InvalidInputException(Map.of(field, message));
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}

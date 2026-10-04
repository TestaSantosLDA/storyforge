package com.storyforge.llm;

/** The engine answered, but not in the required structure. Counts as a failed Format check. */
public class LlmBadOutputException extends RuntimeException {

    private final String raw;

    public LlmBadOutputException(String message, String raw) {
        super(message);
        this.raw = raw;
    }

    public String raw() {
        return raw;
    }
}

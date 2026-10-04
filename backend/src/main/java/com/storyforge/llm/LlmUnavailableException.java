package com.storyforge.llm;

/** The engine could not answer (outage, usage limit, timeout). Not the story's fault, never a strike. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmUnavailableException(String message) {
        super(message);
    }
}

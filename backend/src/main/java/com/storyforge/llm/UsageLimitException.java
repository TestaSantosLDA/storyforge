package com.storyforge.llm;

import java.time.Instant;
import java.util.Optional;

/** The plan's usage limit was reached. Retrying before the reset is pointless, so callers don't back off. */
public class UsageLimitException extends LlmUnavailableException {

    private final Instant resetsAt;

    public UsageLimitException(String message, Instant resetsAt) {
        super(message);
        this.resetsAt = resetsAt;
    }

    /** Empty when the CLI didn't say when the limit resets. */
    public Optional<Instant> resetsAt() {
        return Optional.ofNullable(resetsAt);
    }
}

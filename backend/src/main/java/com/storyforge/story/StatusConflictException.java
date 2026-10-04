package com.storyforge.story;

/** A status change was refused: the story is not where the caller expected, or the move is not allowed. */
public class StatusConflictException extends RuntimeException {

    public StatusConflictException(String message) {
        super(message);
    }
}

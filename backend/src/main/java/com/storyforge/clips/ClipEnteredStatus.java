package com.storyforge.clips;

/** Published inside the transition's transaction; the dispatcher acts on it once that has committed. */
public record ClipEnteredStatus(long jobId, ClipStatus status) {
}

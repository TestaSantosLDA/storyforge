package com.storyforge.story;

/** Published inside the transaction of every status change; listeners act after commit. */
public record StoryEnteredStatus(long storyId, StoryStatus status) {
}

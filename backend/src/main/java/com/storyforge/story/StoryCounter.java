package com.storyforge.story;

/** The retry and rejection counters on a story row, with the config limit each one is checked against. */
public enum StoryCounter {
    STORY_QA_RETRIES,
    STORY_REJECTIONS,
    SCRIPT_QA_RETRIES,
    SCRIPT_REJECTIONS,
    /** Video rejections at final review. Separate from the story and script counters (decided 2026-10-04). */
    FINAL_REJECTIONS
}

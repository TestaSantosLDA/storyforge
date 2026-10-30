package com.storyforge.clips;

/**
 * Clip job and clip statuses (CLAUDE.md "Status values", docs/pipeline-b/clips-from-any-video.md). A job's status says
 * which stage it is working through; each clip's status says what that clip still needs, so a rejection redoes one
 * clip and leaves the others alone. {@code analyzing_rejection} mirrors Pipeline A: Claude routes a rejection first.
 */
public enum ClipStatus {

    INGESTING(true),
    TRANSCRIBING(true),
    ANALYZING_REJECTION(true),
    SELECTING(true),
    RENDERING(true),
    FORMATTING(true),
    CLIP_READY(false),
    CLIP_APPROVED(false),
    CLIP_PUBLISHED(false),
    NEEDS_ATTENTION(false),
    CLIP_KILLED(false);

    private final boolean working;

    ClipStatus(boolean working) {
        this.working = working;
    }

    /** A stage does the work; after a restart it is run again. */
    public boolean working() {
        return working;
    }

    public String dbValue() {
        return name().toLowerCase();
    }

    public static ClipStatus fromDb(String value) {
        return value == null ? null : valueOf(value.toUpperCase());
    }
}

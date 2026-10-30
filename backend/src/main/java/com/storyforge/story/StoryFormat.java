package com.storyforge.story;

import static com.storyforge.story.StoryStatus.*;

import java.util.Map;
import java.util.Optional;

/**
 * What a story turns into, and so which steps it runs (docs/pipeline-c and docs/pipeline-d "Status transitions"). All
 * formats share the statuses; they differ only in where a handoff status leads, so a short skips the steps it has no
 * use for.
 */
public enum StoryFormat {

    /** Pipeline A: storybook fable, landscape, two gates. */
    FABLE(Map.of(
            STORY_APPROVED, SCRIPT_IN_PROGRESS,
            SCRIPT_APPROVED, AUDIO_IN_PROGRESS,
            AUDIO_DONE, VISUALS_IN_PROGRESS,
            VISUALS_DONE, ASSEMBLING,
            FINAL_APPROVED, PREPARING_PUBLISH)),
    /** Pipeline C: text-card short. One gate, no visuals stage: narration goes straight to vertical assembly. */
    STORY_SHORT(Map.of(
            STORY_APPROVED, AUDIO_IN_PROGRESS,
            AUDIO_DONE, ASSEMBLING,
            FINAL_APPROVED, PREPARING_PUBLISH)),
    /** Pipeline D: Motion Pictures. One gate (script, cast and claims), then narration, code-animated scenes, assembly. */
    MOTION_PICTURE(Map.of(
            STORY_APPROVED, AUDIO_IN_PROGRESS,
            AUDIO_DONE, VISUALS_IN_PROGRESS,
            VISUALS_DONE, ASSEMBLING,
            FINAL_APPROVED, PREPARING_PUBLISH));

    private final Map<StoryStatus, StoryStatus> handoffs;

    StoryFormat(Map<StoryStatus, StoryStatus> handoffs) {
        this.handoffs = handoffs;
    }

    /** The working status a handoff status hands to in this format. Empty for a status this format never reaches. */
    public Optional<StoryStatus> next(StoryStatus handoff) {
        return Optional.ofNullable(handoffs.get(handoff));
    }

    /** The database and docs spelling, e.g. {@code motion_picture}. */
    public String dbValue() {
        return name().toLowerCase();
    }

    public static StoryFormat fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}

package com.storyforge.story;

import static com.storyforge.story.StoryStatus.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed story status changes, collected from the "Status transitions" table of every Pipeline A stage doc.
 * A retry inside a stage (e.g. QA fail with budget left) keeps the same status and is not a transition.
 */
public final class StoryTransitions {

    private static final Map<StoryStatus, Set<StoryStatus>> ALLOWED = new EnumMap<>(StoryStatus.class);

    static {
        allow(QUEUED, STORY_IN_PROGRESS);
        allow(STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, NEEDS_ATTENTION, ARCHIVED);
        allow(AWAITING_STORY_APPROVAL, STORY_APPROVED, STORY_IN_PROGRESS, ARCHIVED);
        allow(STORY_APPROVED, SCRIPT_IN_PROGRESS);
        allow(SCRIPT_IN_PROGRESS, AWAITING_SCRIPT_APPROVAL, NEEDS_ATTENTION, ARCHIVED);
        allow(AWAITING_SCRIPT_APPROVAL, SCRIPT_APPROVED, SCRIPT_IN_PROGRESS, ARCHIVED);
        allow(SCRIPT_APPROVED, AUDIO_IN_PROGRESS);
        allow(AUDIO_IN_PROGRESS, AUDIO_DONE, NEEDS_ATTENTION);
        allow(AUDIO_DONE, VISUALS_IN_PROGRESS);
        allow(VISUALS_IN_PROGRESS, VISUALS_DONE, NEEDS_ATTENTION);
        allow(VISUALS_DONE, ASSEMBLING);
        allow(ASSEMBLING, AWAITING_FINAL_REVIEW, NEEDS_ATTENTION);
        allow(AWAITING_FINAL_REVIEW, FINAL_APPROVED, ANALYZING_REJECTION, ARCHIVED);
        allow(ANALYZING_REJECTION, AUDIO_IN_PROGRESS, VISUALS_IN_PROGRESS, ASSEMBLING,
                SCRIPT_IN_PROGRESS, STORY_IN_PROGRESS, NEEDS_ATTENTION);
        allow(FINAL_APPROVED, PREPARING_PUBLISH);
        allow(PREPARING_PUBLISH, READY_TO_UPLOAD, NEEDS_ATTENTION);
        allow(READY_TO_UPLOAD, PUBLISHED, ASSEMBLING, ANALYZING_REJECTION, ARCHIVED);
        allow(PUBLISHED);
        // Resume, retry, accept or "send back to Gate B" all land on a working status; Kill archives.
        Set<StoryStatus> fromAttention = EnumSet.copyOf(StoryStatus.ofKind(StoryStatus.Kind.WORKING));
        fromAttention.add(ARCHIVED);
        ALLOWED.put(NEEDS_ATTENTION, fromAttention);
        allow(ARCHIVED, QUEUED);
    }

    private StoryTransitions() {
    }

    private static void allow(StoryStatus from, StoryStatus... to) {
        Set<StoryStatus> set = EnumSet.noneOf(StoryStatus.class);
        Collections.addAll(set, to);
        ALLOWED.put(from, set);
    }

    public static boolean isAllowed(StoryStatus from, StoryStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<StoryStatus> from(StoryStatus status) {
        return Set.copyOf(ALLOWED.getOrDefault(status, Set.of()));
    }
}

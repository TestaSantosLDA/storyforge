package com.storyforge.story;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StoryStatusTest {

    @Test
    void db_values_match_the_spelling_in_claude_md() {
        assertThat(STORY_IN_PROGRESS.dbValue()).isEqualTo("story_in_progress");
        assertThat(fromDb("awaiting_final_review")).isEqualTo(AWAITING_FINAL_REVIEW);
        assertThat(Arrays.stream(values()).map(StoryStatus::dbValue)).containsExactly(
                "queued", "story_in_progress", "awaiting_story_approval", "story_approved", "script_in_progress",
                "awaiting_script_approval", "script_approved", "audio_in_progress", "audio_done",
                "visuals_in_progress", "visuals_done", "assembling", "awaiting_final_review", "analyzing_rejection",
                "final_approved", "preparing_publish", "ready_to_upload", "published", "needs_attention", "archived");
    }

    @Test
    void working_statuses_include_the_ones_without_in_progress_in_their_name() {
        // Design review gap 2: the restart rule must not key off the "_in_progress" suffix.
        assertThat(ofKind(Kind.WORKING)).contains(ASSEMBLING, ANALYZING_REJECTION, PREPARING_PUBLISH);
    }

    @ParameterizedTest
    @EnumSource(value = StoryStatus.class, names = {"STORY_APPROVED", "SCRIPT_APPROVED", "AUDIO_DONE",
            "VISUALS_DONE", "FINAL_APPROVED"})
    void every_handoff_hands_to_an_allowed_working_status(StoryStatus handoff) {
        assertThat(handoff.kind()).isEqualTo(Kind.HANDOFF);
        StoryStatus next = handoff.handoffTarget().orElseThrow();
        assertThat(next.kind()).isEqualTo(Kind.WORKING);
        assertThat(StoryTransitions.isAllowed(handoff, next)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(StoryStatus.class)
    void only_published_is_a_dead_end(StoryStatus s) {
        assertThat(StoryTransitions.from(s).isEmpty()).isEqualTo(s == PUBLISHED);
    }

    @ParameterizedTest
    @EnumSource(StoryStatus.class)
    void every_working_status_can_be_flagged_and_resumed(StoryStatus s) {
        if (s.kind() == Kind.WORKING) {
            assertThat(StoryTransitions.isAllowed(s, NEEDS_ATTENTION)).as(s.dbValue()).isTrue();
            assertThat(StoryTransitions.isAllowed(NEEDS_ATTENTION, s)).as(s.dbValue()).isTrue();
        }
    }

    @Test
    void transitions_from_the_stage_docs_are_allowed() {
        assertThat(StoryTransitions.isAllowed(QUEUED, STORY_IN_PROGRESS)).isTrue();
        assertThat(StoryTransitions.isAllowed(AWAITING_STORY_APPROVAL, STORY_IN_PROGRESS)).isTrue();
        assertThat(StoryTransitions.isAllowed(NEEDS_ATTENTION, SCRIPT_IN_PROGRESS)).isTrue(); // Stage 2: back to Gate B
        assertThat(StoryTransitions.isAllowed(ANALYZING_REJECTION, VISUALS_IN_PROGRESS)).isTrue();
        assertThat(StoryTransitions.isAllowed(READY_TO_UPLOAD, ASSEMBLING)).isTrue(); // Stage 5: music claim
        assertThat(StoryTransitions.isAllowed(ARCHIVED, QUEUED)).isTrue();
    }

    @Test
    void skipping_steps_is_not_allowed() {
        assertThat(StoryTransitions.isAllowed(QUEUED, SCRIPT_IN_PROGRESS)).isFalse();
        assertThat(StoryTransitions.isAllowed(AUDIO_IN_PROGRESS, VISUALS_IN_PROGRESS)).isFalse();
        assertThat(StoryTransitions.isAllowed(PUBLISHED, ARCHIVED)).isFalse();
        assertThat(StoryTransitions.isAllowed(AUDIO_IN_PROGRESS, ARCHIVED)).isFalse(); // past Gate B: flag, not archive
    }

    @Test
    void stories_at_gates_and_flags_count_as_in_flight() {
        assertThat(AWAITING_SCRIPT_APPROVAL.inFlight()).isTrue();
        assertThat(NEEDS_ATTENTION.inFlight()).isTrue();
        assertThat(QUEUED.inFlight()).isFalse();
        assertThat(ARCHIVED.inFlight()).isFalse();
        assertThat(PUBLISHED.inFlight()).isFalse();
    }
}

package com.storyforge.story;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.DbTest;
import com.storyforge.log.StatusChange;
import com.storyforge.log.StatusChangeRepository;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StoryStatusServiceTest extends DbTest {

    static final String USER = "user:tester";
    static final String SYS = "system:test";

    @Autowired
    StoryStatusService service;
    @Autowired
    StoryRepository stories;
    @Autowired
    TopicRepository topics;
    @Autowired
    StatusChangeRepository changes;

    Topic topic;

    @BeforeEach
    void topic() {
        topic = topics.save(new Topic("Fox & Owl in Whisperwood", "A misty wood where small animals learn big lessons."));
    }

    Story add(String concept) {
        return service.enqueue(topic, concept, "Listening matters", 4, null, USER);
    }

    Story reload(Story s) {
        return stories.findById(s.getId()).orElseThrow();
    }

    @Test
    void stage0_s1_run_starts_the_first_story_and_snapshots_the_topic() {
        Story first = add("first");
        add("second");

        RunResult result = service.runNext(USER);

        assertThat(result).isEqualTo(new RunResult.Started(first.getId()));
        Story started = reload(first);
        assertThat(started.getStatus()).isEqualTo(STORY_IN_PROGRESS);
        assertThat(started.getQueuePosition()).isNull();
        assertThat(started.getTopicSnapshot()).isEqualTo(topic.getDescription());
    }

    @Test
    void stage0_s5_topic_edits_do_not_change_an_in_flight_snapshot() {
        Story s = add("first");
        service.runNext(USER);
        topic.setDescription("A completely different wood.");
        topics.save(topic);

        assertThat(reload(s).getTopicSnapshot()).isEqualTo("A misty wood where small animals learn big lessons.");
    }

    @Test
    void stage0_s7_run_with_an_empty_queue_does_nothing() {
        assertThat(service.runNext(USER)).isInstanceOf(RunResult.NothingToRun.class);
    }

    @Test
    void stage0_s10_run_refuses_at_the_concurrency_cap() {
        Story s = add("first");
        Story waiting = add("second");
        service.runNext(USER);
        // A story waiting at a gate still counts as in flight.
        service.transition(s.getId(), STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, SYS, "qa passed");

        assertThat(service.runNext(USER)).isEqualTo(new RunResult.AtCapacity(1, 1));
        assertThat(reload(waiting).getStatus()).isEqualTo(QUEUED);
    }

    @Test
    void every_status_change_is_logged_with_trigger_and_reason() {
        Story s = add("first");
        service.runNext(USER);
        service.transition(s.getId(), STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, SYS, "qa passed");

        assertThat(changes.findByStoryIdOrderByIdAsc(s.getId()))
                .extracting(StatusChange::getFromStatus, StatusChange::getToStatus, StatusChange::getTriggeredBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, QUEUED, USER),
                        org.assertj.core.groups.Tuple.tuple(QUEUED, STORY_IN_PROGRESS, USER),
                        org.assertj.core.groups.Tuple.tuple(STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, SYS));
    }

    @Test
    void a_stale_expected_status_is_refused() {
        Story s = add("first");
        service.runNext(USER);

        assertThatThrownBy(() -> service.transition(s.getId(), QUEUED, STORY_IN_PROGRESS, USER, "double"))
                .isInstanceOf(StatusConflictException.class)
                .hasMessageContaining("expected queued");
    }

    @Test
    void a_move_not_in_the_docs_is_refused_and_nothing_changes() {
        Story s = add("first");
        service.runNext(USER);

        assertThatThrownBy(() -> service.transition(s.getId(), STORY_IN_PROGRESS, AUDIO_IN_PROGRESS, SYS, "skip"))
                .isInstanceOf(StatusConflictException.class);
        assertThat(reload(s).getStatus()).isEqualTo(STORY_IN_PROGRESS);
    }

    @Test
    void needs_attention_stores_the_resume_target_and_resume_goes_back_to_it() {
        Story s = advanceToAudio(add("first"));

        service.flag(s.getId(), AUDIO_IN_PROGRESS, "sidecar down after retries", SYS);
        Story flagged = reload(s);
        assertThat(flagged.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(flagged.getResumeStatus()).isEqualTo(AUDIO_IN_PROGRESS);
        assertThat(flagged.getAttentionReason()).isEqualTo("sidecar down after retries");

        service.resume(s.getId(), USER, "sidecar restarted");
        Story resumed = reload(s);
        assertThat(resumed.getStatus()).isEqualTo(AUDIO_IN_PROGRESS);
        assertThat(resumed.getResumeStatus()).isNull();
        assertThat(resumed.getAttentionReason()).isNull();
    }

    @Test
    void stage2_s6_needs_attention_can_send_the_story_back_to_gate_b() {
        Story s = advanceToAudio(add("first"));
        service.flag(s.getId(), AUDIO_IN_PROGRESS, "line keeps failing", SYS);

        service.resumeTo(s.getId(), SCRIPT_IN_PROGRESS, USER, "rewrite the line");

        assertThat(reload(s).getStatus()).isEqualTo(SCRIPT_IN_PROGRESS);
    }

    @Test
    void only_working_statuses_can_be_flagged() {
        Story s = add("first");
        service.runNext(USER);
        service.transition(s.getId(), STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, SYS, "qa passed");

        assertThatThrownBy(() -> service.flag(s.getId(), AWAITING_STORY_APPROVAL, "x", SYS))
                .isInstanceOf(StatusConflictException.class);
    }

    @Test
    void stage1_s5_budget_exhausted_archives_with_a_reason() {
        Story s = add("first");
        service.runNext(USER);

        service.archive(s.getId(), STORY_IN_PROGRESS, ArchiveReason.STORY_QA_RETRIES_EXHAUSTED, SYS, null);

        Story archived = reload(s);
        assertThat(archived.getStatus()).isEqualTo(ARCHIVED);
        assertThat(archived.getArchiveReason()).isEqualTo(ArchiveReason.STORY_QA_RETRIES_EXHAUSTED);
    }

    @Test
    void stage0_s11_restore_goes_to_the_bottom_with_counters_reset() {
        Story s = add("first");
        Story other = add("second");
        service.runNext(USER);
        jdbc.sql("update story set story_qa_retries = 5, script_rejections = 2, final_rejections = 1 where id = :id")
                .param("id", s.getId()).update();
        service.archive(s.getId(), STORY_IN_PROGRESS, ArchiveReason.STORY_QA_RETRIES_EXHAUSTED, SYS, null);

        service.restore(s.getId(), USER);

        Story restored = reload(s);
        assertThat(restored.getStatus()).isEqualTo(QUEUED);
        assertThat(restored.getQueuePosition()).isGreaterThan(reload(other).getQueuePosition());
        assertThat(restored.getStoryQaRetries()).isZero();
        assertThat(restored.getScriptRejections()).isZero();
        assertThat(restored.getFinalRejections()).isZero();
        assertThat(restored.getArchiveReason()).isNull();
        assertThat(changes.findByStoryIdOrderByIdAsc(s.getId())).hasSize(4); // history kept
    }

    @Test
    void special_targets_need_their_own_method() {
        Story s = add("first");
        service.runNext(USER);
        assertThatThrownBy(() -> service.transition(s.getId(), STORY_IN_PROGRESS, NEEDS_ATTENTION, SYS, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Story advanceToAudio(Story s) {
        long id = s.getId();
        service.runNext(USER);
        service.transition(id, STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, SYS, "qa passed");
        service.transition(id, AWAITING_STORY_APPROVAL, STORY_APPROVED, USER, "approve");
        service.transition(id, STORY_APPROVED, SCRIPT_IN_PROGRESS, SYS, "chain");
        service.transition(id, SCRIPT_IN_PROGRESS, AWAITING_SCRIPT_APPROVAL, SYS, "qa passed");
        service.transition(id, AWAITING_SCRIPT_APPROVAL, SCRIPT_APPROVED, USER, "approve");
        service.transition(id, SCRIPT_APPROVED, AUDIO_IN_PROGRESS, SYS, "chain");
        return reload(s);
    }
}

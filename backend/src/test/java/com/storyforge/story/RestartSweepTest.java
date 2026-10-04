package com.storyforge.story;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import com.storyforge.gpu.GpuLease;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Stage 0 scenario 12 and every stage's "app restarts" scenario, via one sweep. */
@Import(RestartSweepTest.RecordingDispatcherConfig.class)
class RestartSweepTest extends DbTest {

    @Autowired
    RestartSweep sweep;
    @Autowired
    StoryStatusService service;
    @Autowired
    StoryRepository stories;
    @Autowired
    TopicRepository topics;
    @Autowired
    RecordingDispatcher dispatcher;
    @Autowired
    GpuLease gpu;

    @Test
    void handoffs_advance_working_statuses_are_redispatched_and_the_rest_are_left_alone() {
        Topic topic = topics.save(new Topic("T", "D"));
        long handoff = startedStory(topic, "handoff");
        service.transition(handoff, STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, "system:t", "qa");
        service.transition(handoff, AWAITING_STORY_APPROVAL, STORY_APPROVED, "user:t", "approve");
        long working = startedStory(topic, "working");
        long gate = startedStory(topic, "gate");
        service.transition(gate, STORY_IN_PROGRESS, AWAITING_STORY_APPROVAL, "system:t", "qa");
        long queued = service.enqueue(topic, "queued", "m", 4, null, "user:t").getId();
        gpu.tryAcquire("crashed-process");
        dispatcher.calls.clear();

        sweep.run();

        assertThat(status(handoff)).isEqualTo(SCRIPT_IN_PROGRESS);
        assertThat(status(working)).isEqualTo(STORY_IN_PROGRESS);
        assertThat(status(gate)).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(status(queued)).isEqualTo(QUEUED);
        assertThat(dispatcher.calls).containsExactlyInAnyOrder(
                handoff + ":" + SCRIPT_IN_PROGRESS.dbValue(), working + ":" + STORY_IN_PROGRESS.dbValue());
        assertThat(gpu.tryAcquire("new-process")).as("leftover GPU lease cleared").isTrue();
    }

    private long startedStory(Topic topic, String concept) {
        long id = service.enqueue(topic, concept, "m", 4, null, "user:t").getId();
        // Bypass the concurrency cap for setup: start this specific story directly.
        jdbc.sql("update story set status = 'story_in_progress', queue_position = null where id = :id")
                .param("id", id).update();
        return id;
    }

    private StoryStatus status(long id) {
        return stories.findById(id).orElseThrow().getStatus();
    }

    static class RecordingDispatcher implements StageDispatcher {
        final List<String> calls = new ArrayList<>();

        @Override
        public void dispatch(long storyId, StoryStatus status) {
            calls.add(storyId + ":" + status.dbValue());
        }
    }

    @TestConfiguration
    static class RecordingDispatcherConfig {
        @Bean
        RecordingDispatcher recordingDispatcher() {
            return new RecordingDispatcher();
        }
    }
}

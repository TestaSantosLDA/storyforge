package com.storyforge.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "storyforge.pipeline.story-cost-budget-usd=1.00")
class AttemptLogTest extends DbTest {

    @Autowired
    AttemptLog attemptLog;
    @Autowired
    AttemptRepository attempts;
    @Autowired
    StoryStatusService service;
    @Autowired
    StoryRepository stories;
    @Autowired
    TopicRepository topics;

    @Test
    void attempts_are_logged_and_crossing_the_cost_budget_flags_the_story() {
        Topic topic = topics.save(new Topic("T", "D"));
        long id = service.enqueue(topic, "c", "m", 4, null, "user:t").getId();
        service.runNext("user:t");

        boolean ok = attemptLog.record(attempt(id, 1, "0.60"));
        assertThat(ok).isTrue();
        assertThat(story(id).getStatus()).isEqualTo(StoryStatus.STORY_IN_PROGRESS);

        ok = attemptLog.record(attempt(id, 2, "0.60"));
        assertThat(ok).isFalse();
        Story flagged = story(id);
        assertThat(flagged.getStatus()).isEqualTo(StoryStatus.NEEDS_ATTENTION);
        assertThat(flagged.getResumeStatus()).isEqualTo(StoryStatus.STORY_IN_PROGRESS);
        assertThat(flagged.getAttentionReason()).contains("cost budget exceeded");
        assertThat(flagged.getCostUsd()).isEqualByComparingTo("1.20");
        assertThat(attempts.findByStoryIdOrderByIdAsc(id)).hasSize(2);
    }

    private Attempt attempt(long storyId, int no, String cost) {
        return new Attempt(storyId, "stage1", "story", no)
                .versions("story-v1", "claude-test", null)
                .inputs("abc", "{\"concept\":\"c\"}")
                .result("qa_failed", "raw text", "{\"structure\":\"fail\"}", "{\"story_qa_retries\":" + no + "}",
                        new BigDecimal(cost), null);
    }

    private Story story(long id) {
        return stories.findById(id).orElseThrow();
    }
}

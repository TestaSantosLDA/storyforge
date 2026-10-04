package com.storyforge.story;

import com.storyforge.config.StoryforgeProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Increments a counter under a row lock and says whether it has reached its limit. */
@Component
public class StoryCounters {

    private final StoryRepository stories;
    private final StoryforgeProperties.Budgets budgets;

    StoryCounters(StoryRepository stories, StoryforgeProperties props) {
        this.stories = stories;
        this.budgets = props.budgets();
    }

    public record Count(int value, int limit) {
        public boolean reachedLimit() {
            return value >= limit;
        }
    }

    @Transactional
    public Count increment(long storyId, StoryCounter counter) {
        Story story = stories.findByIdForUpdate(storyId).orElseThrow();
        return new Count(story.increment(counter), limit(counter));
    }

    public int limit(StoryCounter counter) {
        return switch (counter) {
            case STORY_QA_RETRIES -> budgets.storyQaRetries();
            case STORY_REJECTIONS -> budgets.storyRejections();
            case SCRIPT_QA_RETRIES -> budgets.scriptQaRetries();
            case SCRIPT_REJECTIONS -> budgets.scriptRejections();
            case FINAL_REJECTIONS -> budgets.finalRejections();
        };
    }
}

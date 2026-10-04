package com.storyforge.story;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Adds attempt cost to the story row under a row lock. */
@Component
public class StoryCosts {

    private final StoryRepository stories;

    StoryCosts(StoryRepository stories) {
        this.stories = stories;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Story add(long storyId, BigDecimal usd) {
        Story story = stories.findByIdForUpdate(storyId).orElseThrow();
        story.addCost(usd);
        return story;
    }
}

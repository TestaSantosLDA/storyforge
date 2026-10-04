package com.storyforge.log;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.story.Story;
import com.storyforge.story.StoryCosts;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records attempts and adds their cost to the story. Crossing {@code story-cost-budget-usd} flags the story,
 * so a runaway retry loop can't keep spending (design review risk 3).
 */
@Service
public class AttemptLog {

    private static final Logger log = LoggerFactory.getLogger(AttemptLog.class);

    private final AttemptRepository attempts;
    private final StoryCosts costs;
    private final StoryStatusService status;
    private final StoryforgeProperties props;

    AttemptLog(AttemptRepository attempts, StoryCosts costs, StoryStatusService status, StoryforgeProperties props) {
        this.attempts = attempts;
        this.costs = costs;
        this.status = status;
        this.props = props;
    }

    /** Saves a finished attempt. Returns true if the story is still within its cost budget. */
    @Transactional
    public boolean record(Attempt attempt) {
        attempts.save(attempt);
        Story story = costs.add(attempt.getStoryId(), attempt.getCostUsd());
        if (story.getCostUsd().compareTo(props.pipeline().storyCostBudgetUsd()) <= 0) {
            return true;
        }
        if (story.getStatus().kind() == StoryStatus.Kind.WORKING) {
            status.flag(story.getId(), story.getStatus(),
                    "cost budget exceeded: $" + story.getCostUsd() + " > $" + props.pipeline().storyCostBudgetUsd(),
                    "system:attempt-log");
        } else {
            log.warn("story {} over cost budget in {}", story.getId(), story.getStatus().dbValue());
        }
        return false;
    }
}

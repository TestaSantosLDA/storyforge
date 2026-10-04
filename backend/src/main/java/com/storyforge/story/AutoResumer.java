package com.storyforge.story;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes stories flagged with an {@code auto_resume_at} time once it has passed (e.g. after a usage-limit reset).
 * The time is stored on the row, so a restart while waiting changes nothing.
 */
@Component
@ConditionalOnProperty(name = "storyforge.pipeline.auto-resume", havingValue = "true", matchIfMissing = true)
public class AutoResumer {

    private static final Logger log = LoggerFactory.getLogger(AutoResumer.class);

    private final StoryRepository stories;
    private final StoryStatusService status;

    AutoResumer(StoryRepository stories, StoryStatusService status) {
        this.stories = stories;
        this.status = status;
    }

    @Scheduled(fixedDelayString = "${storyforge.pipeline.auto-resume-check:60s}")
    public void resumeDue() {
        for (Long id : stories.findDueForAutoResume(Instant.now())) {
            try {
                status.resume(id, "system:auto-resume", "usage limit reset; resuming automatically");
            } catch (StatusConflictException e) {
                log.info("story {} not auto-resumed: {}", id, e.getMessage());
            }
        }
    }
}

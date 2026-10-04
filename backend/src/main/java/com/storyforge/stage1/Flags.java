package com.storyforge.stage1;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.llm.UsageLimitException;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flags a story when Claude can't answer. A usage limit flags it with an automatic resume at the reset time (or after
 * {@code storyforge.llm.limit-retry-after} when no time is given); any other outage waits for a person.
 */
@Component
class Flags {

    private static final Logger log = LoggerFactory.getLogger(Flags.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final StoryStatusService status;
    private final StoryforgeProperties.Llm config;

    Flags(StoryStatusService status, StoryforgeProperties props) {
        this.status = status;
        this.config = props.llm();
    }

    void unavailable(long storyId, StoryStatus from, LlmUnavailableException e, String trigger) {
        try {
            if (e instanceof UsageLimitException limit) {
                Instant at = limit.resetsAt().orElse(Instant.now().plus(config.limitRetryAfter()));
                status.flag(storyId, from, "Claude usage limit reached; resumes automatically at " + HHMM.format(at)
                        + (limit.resetsAt().isEmpty() ? " (reset time unknown, retrying)" : ""), trigger, at);
            } else {
                status.flag(storyId, from, "Claude unavailable after retries: " + e.getMessage(), trigger);
            }
        } catch (StatusConflictException ignored) {
            log.info("story {} not flagged: it moved on", storyId);
        }
    }
}

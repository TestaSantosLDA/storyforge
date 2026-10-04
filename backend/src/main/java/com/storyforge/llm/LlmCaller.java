package com.storyforge.llm;

import com.storyforge.config.StoryforgeProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls the configured engine, backing off and retrying when it is unavailable (CLAUDE.md rule 6). After the last
 * retry the {@link LlmUnavailableException} reaches the stage, which flags the story without a strike.
 */
@Component
public class LlmCaller {

    private static final Logger log = LoggerFactory.getLogger(LlmCaller.class);

    private final LlmEngine engine;
    private final StoryforgeProperties.Llm config;

    LlmCaller(LlmEngine engine, StoryforgeProperties props) {
        this.engine = engine;
        this.config = props.llm();
    }

    public LlmResult call(LlmRequest req) {
        Duration wait = config.firstBackoff();
        for (int attempt = 0; ; attempt++) {
            try {
                return engine.complete(req);
            } catch (UsageLimitException e) {
                throw e; // waiting seconds won't help; the stage flags it with the reset time
            } catch (LlmUnavailableException e) {
                if (attempt >= config.unavailableRetries()) {
                    throw e;
                }
                log.warn("{} unavailable ({}); retrying in {}", engine.name(), e.getMessage(), wait);
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
    }

    public String engineName() {
        return engine.name();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("interrupted while backing off", e);
        }
    }
}

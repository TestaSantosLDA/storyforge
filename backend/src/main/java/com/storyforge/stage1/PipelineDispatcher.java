package com.storyforge.stage1;

import com.storyforge.story.StageDispatcher;
import com.storyforge.story.StoryEnteredStatus;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the stage that owns a working status, once the status change has committed. Plain Spring code decides the
 * flow (CLAUDE.md rule 2). One worker thread: stories run one step at a time. The "running" set only stops the same
 * story being started twice in this process; the status column stays the source of truth.
 */
@Component
@ConditionalOnProperty(name = "storyforge.pipeline.auto-dispatch", havingValue = "true", matchIfMissing = true)
public class PipelineDispatcher implements StageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PipelineDispatcher.class);

    private final StoryStep storyStep;
    private final ScriptStep scriptStep;
    private final StoryStatusService status;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    PipelineDispatcher(StoryStep storyStep, ScriptStep scriptStep, StoryStatusService status) {
        this.storyStep = storyStep;
        this.scriptStep = scriptStep;
        this.status = status;
    }

    @TransactionalEventListener
    void onStatus(StoryEnteredStatus e) {
        if (e.status().kind() == StoryStatus.Kind.WORKING) {
            dispatch(e.storyId(), e.status());
        }
    }

    @Override
    public void dispatch(long storyId, StoryStatus s) {
        Runnable step = switch (s) {
            case STORY_IN_PROGRESS -> () -> storyStep.run(storyId);
            case SCRIPT_IN_PROGRESS -> () -> scriptStep.run(storyId);
            default -> null;
        };
        if (step == null) {
            log.info("story {} is {}; no stage is implemented for it yet", storyId, s.dbValue());
            return;
        }
        if (!running.add(storyId)) {
            return;
        }
        worker.submit(() -> {
            try {
                step.run();
            } catch (RuntimeException ex) {
                log.error("story {} step failed", storyId, ex);
                try {
                    status.flag(storyId, s, "unexpected error: " + ex.getMessage(), "system:pipeline");
                } catch (RuntimeException ignored) {
                    // already moved on
                }
            } finally {
                running.remove(storyId);
            }
        });
    }
}

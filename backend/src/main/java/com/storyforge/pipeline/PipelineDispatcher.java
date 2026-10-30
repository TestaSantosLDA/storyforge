package com.storyforge.pipeline;

import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioStep;
import com.storyforge.stage3.VisualsStep;
import com.storyforge.stage4.AssemblyStep;
import com.storyforge.stage4.FinalReview;
import com.storyforge.stage5.PublishStep;

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
 * step of the same story being queued twice in this process; it is keyed by story and status, so a step that moves
 * its story straight on (visuals → assembling) queues the next step behind itself. The status column stays the
 * source of truth.
 */
@Component
@ConditionalOnProperty(name = "storyforge.pipeline.auto-dispatch", havingValue = "true", matchIfMissing = true)
public class PipelineDispatcher implements StageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PipelineDispatcher.class);

    private final StoryStep storyStep;
    private final ScriptStep scriptStep;
    private final AudioStep audioStep;
    private final VisualsStep visualsStep;
    private final AssemblyStep assemblyStep;
    private final FinalReview finalReview;
    private final PublishStep publishStep;
    private final StoryStatusService status;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    PipelineDispatcher(StoryStep storyStep, ScriptStep scriptStep, AudioStep audioStep, VisualsStep visualsStep,
            AssemblyStep assemblyStep, FinalReview finalReview, PublishStep publishStep,
            StoryStatusService status) {
        this.storyStep = storyStep;
        this.scriptStep = scriptStep;
        this.audioStep = audioStep;
        this.visualsStep = visualsStep;
        this.assemblyStep = assemblyStep;
        this.finalReview = finalReview;
        this.publishStep = publishStep;
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
            case AUDIO_IN_PROGRESS -> () -> audioStep.run(storyId);
            case VISUALS_IN_PROGRESS -> () -> visualsStep.run(storyId);
            case ASSEMBLING -> () -> assemblyStep.run(storyId);
            case ANALYZING_REJECTION -> () -> finalReview.analyze(storyId);
            case PREPARING_PUBLISH -> () -> publishStep.run(storyId);
            default -> null;
        };
        if (step == null) {
            log.info("story {} is {}; no stage is implemented for it yet", storyId, s.dbValue());
            return;
        }
        String key = storyId + ":" + s.name();
        if (!running.add(key)) {
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
                running.remove(key);
            }
        });
    }
}

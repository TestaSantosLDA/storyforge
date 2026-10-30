package com.storyforge.clips;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the stage that owns a clip job's working status, once the change has committed. Its own worker thread, so a
 * long download or render never holds up Pipeline A; clip jobs run one step at a time. After a restart, working jobs
 * run their stage again.
 */
@Component
@ConditionalOnProperty(name = "storyforge.pipeline.auto-dispatch", havingValue = "true", matchIfMissing = true)
public class ClipDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClipDispatcher.class);

    private final ClipJobs jobs;
    private final IntakeStep intake;
    private final SelectStep select;
    private final RenderStep render;
    private final FormatStep format;
    private final ClipReview review;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    ClipDispatcher(ClipJobs jobs, IntakeStep intake, SelectStep select, RenderStep render, FormatStep format,
            ClipReview review) {
        this.jobs = jobs;
        this.intake = intake;
        this.select = select;
        this.render = render;
        this.format = format;
        this.review = review;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        jobs.working().forEach(j -> dispatch(j.id(), j.status()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStatus(ClipEnteredStatus e) {
        if (e.status().working()) {
            dispatch(e.jobId(), e.status());
        }
    }

    private void dispatch(long jobId, ClipStatus s) {
        Runnable step = switch (s) {
            case INGESTING -> () -> intake.ingest(jobId);
            case TRANSCRIBING -> () -> intake.transcribe(jobId);
            case ANALYZING_REJECTION -> () -> review.analyze(jobId);
            case SELECTING -> () -> select.run(jobId);
            case RENDERING -> () -> render.run(jobId);
            case FORMATTING -> () -> format.run(jobId);
            default -> null;
        };
        String key = jobId + ":" + s.name();
        if (step == null || !running.add(key)) {
            return;
        }
        worker.submit(() -> {
            try {
                step.run();
            } catch (RuntimeException ex) {
                log.error("clip job {} step failed", jobId, ex);
                try {
                    jobs.flag(jobId, s, "unexpected error: " + ex.getMessage(), "system:clip-pipeline");
                } catch (RuntimeException ignored) {
                    // already moved on
                }
            } finally {
                running.remove(key);
            }
        });
    }
}

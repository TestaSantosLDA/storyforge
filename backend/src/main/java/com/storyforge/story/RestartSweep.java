package com.storyforge.story;

import com.storyforge.gpu.GpuLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One restart rule for every status, driven by {@link StoryStatus.Kind} (docs/foundations.md "Restart sweep"):
 * handoff statuses are advanced to the working status they hand to, working statuses are dispatched again (stages
 * are idempotent thanks to input-hash caching), and human, queued and terminal statuses are left alone.
 */
@Component
public class RestartSweep {

    private static final Logger log = LoggerFactory.getLogger(RestartSweep.class);
    private static final String TRIGGER = "system:restart-sweep";

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final ObjectProvider<StageDispatcher> dispatcher;
    private final GpuLease gpu;

    RestartSweep(StoryRepository stories, StoryStatusService status, ObjectProvider<StageDispatcher> dispatcher,
            GpuLease gpu) {
        this.stories = stories;
        this.status = status;
        this.dispatcher = dispatcher;
        this.gpu = gpu;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    public void run() {
        gpu.releaseAll();
        for (Story s : stories.findByStatusIn(StoryStatus.ofKind(StoryStatus.Kind.HANDOFF))) {
            StoryStatus next = s.getStatus().handoffTarget().orElseThrow();
            try {
                status.transition(s.getId(), s.getStatus(), next, TRIGGER, "handoff not picked up before restart");
            } catch (StatusConflictException e) {
                log.info("story {} moved on during the sweep: {}", s.getId(), e.getMessage());
            }
        }
        for (Story s : stories.findByStatusIn(StoryStatus.ofKind(StoryStatus.Kind.WORKING))) {
            StageDispatcher d = dispatcher.getIfAvailable();
            if (d == null) {
                log.info("story {} is {}; no stage is implemented for it yet", s.getId(), s.getStatus().dbValue());
            } else {
                d.dispatch(s.getId(), s.getStatus());
            }
        }
    }
}

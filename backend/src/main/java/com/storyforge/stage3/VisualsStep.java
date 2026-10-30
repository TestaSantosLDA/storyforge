package com.storyforge.stage3;

import com.storyforge.animation.AnimationEngine;
import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.gpu.GpuLease;
import com.storyforge.image.ImageEngineUnavailableException;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage1.StoryContent;
import com.storyforge.stage2.AudioLines;
import com.storyforge.storage.AssetStorage;
import com.storyforge.storage.InputHash;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage 3: plan shots (3A), draw and check one image per shot (3B), animate each into a clip of exactly its length
 * (3C). Everything already made is reused, so a resume only does what is missing.
 */
@Component
public class VisualsStep {

    static final String TRIGGER = "system:stage3-visuals";
    private static final Logger log = LoggerFactory.getLogger(VisualsStep.class);

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final Drafts drafts;
    private final AudioLines audio;
    private final CharacterStore characters;
    private final ShotPlanner planner;
    private final ShotImages images;
    private final ShotStore store;
    private final AnimationEngine animator;
    private final AssetStorage assets;
    private final GpuLease gpu;
    private final StoryforgeProperties props;

    VisualsStep(StoryRepository stories, StoryStatusService status, Drafts drafts, AudioLines audio,
            CharacterStore characters, ShotPlanner planner, ShotImages images, ShotStore store,
            AnimationEngine animator, AssetStorage assets, GpuLease gpu, StoryforgeProperties props) {
        this.stories = stories;
        this.status = status;
        this.drafts = drafts;
        this.audio = audio;
        this.characters = characters;
        this.planner = planner;
        this.images = images;
        this.store = store;
        this.animator = animator;
        this.assets = assets;
        this.gpu = gpu;
        this.props = props;
    }

    public void run(long storyId) {
        Story story = stories.findWithTopic(storyId).orElseThrow();
        if (story.getStatus() != StoryStatus.VISUALS_IN_PROGRESS) {
            return;
        }
        try {
            ShotPlan plan = plan(story);
            if (plan == null) {
                return;
            }
            Map<String, CharacterFile> cast = new LinkedHashMap<>();
            characters.all().forEach(c -> cast.put(c.id(), c));

            List<String> failing = new ArrayList<>();
            String holder = "pipeline-a:story-" + storyId;
            acquireGpu(holder);
            try {
                for (ShotPlan.Shot shot : plan.shots()) {
                    if (stories.findById(storyId).orElseThrow().getStatus() != StoryStatus.VISUALS_IN_PROGRESS) {
                        return;
                    }
                    if (!images.make(storyId, shot, cast)) {
                        failing.add(shot.id());
                    }
                }
            } finally {
                gpu.release(holder);
            }
            if (!failing.isEmpty()) {
                flag(storyId, failing.size() + " shot(s) failed QA " + props.visuals().imageRetries() + " times: "
                        + String.join(", ", failing) + ". Retry, or accept the best image for each.");
                return;
            }
            for (ShotPlan.Shot shot : plan.shots()) {
                animate(storyId, shot);
            }
            status.transition(storyId, StoryStatus.VISUALS_IN_PROGRESS, StoryStatus.VISUALS_DONE, TRIGGER,
                    plan.shots().size() + " shots animated");
            // Stage 4 starts automatically when visuals are done.
            status.handOff(storyId, StoryStatus.VISUALS_DONE, TRIGGER, "assembling the video");
        } catch (LlmUnavailableException e) {
            flag(storyId, "Claude unavailable after retries: " + e.getMessage());
        } catch (ImageEngineUnavailableException e) {
            flag(storyId, "Image sidecar unavailable: " + e.getMessage() + ". Start the sidecar, then Resume.");
        } catch (AnimationEngine.AnimationException e) {
            flag(storyId, "Animation failed: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("story {} moved on during visuals: {}", storyId, e.getMessage());
        }
    }

    /** The stored plan, or a new one from Claude (kept as an approved 'shots' draft; Stage 3 has no human gate). */
    private ShotPlan plan(Story story) {
        long id = story.getId();
        var existing = drafts.approved(id, Drafts.Kind.SHOTS);
        if (existing.isPresent()) {
            // Re-timed from the current manifest: a corrected audio line can change every later shot's time.
            AudioLines.Track track = audio.track(id).orElseThrow();
            return ShotPlanner.time(ShotPlanner.JSON.readValue(existing.get().content(), ShotPlan.class),
                    ShotPlanner.timing(track.manifest()), track.durationSec());
        }
        ScriptContent script = ShotPlanner.JSON.readValue(
                drafts.approved(id, Drafts.Kind.SCRIPT).orElseThrow().content(), ScriptContent.class);
        StoryContent storyContent = ShotPlanner.JSON.readValue(
                drafts.approved(id, Drafts.Kind.STORY).orElseThrow().content(), StoryContent.class);
        AudioLines.Track track = audio.track(id).orElseThrow();
        List<String> castIds = new ArrayList<>();
        storyContent.reusedCharacters().forEach(r -> castIds.add(r.id()));
        storyContent.newCharacters().forEach(n -> castIds.add(n.id()));
        List<CharacterFile> cast = castIds.stream().map(characters::get).flatMap(java.util.Optional::stream).toList();
        ShotPlanner.Result r = planner.plan(id, story.getTopicSnapshot(), script, cast,
                ShotPlanner.timing(track.manifest()), track.durationSec());
        if (!r.ok()) {
            flag(id, "Shot plan failed checks " + props.visuals().shotPlanRetries() + " times: "
                    + r.failures().stream().map(c -> c.name() + ": " + c.feedback()).reduce((a, b) -> a + "; " + b)
                    .orElse(""));
            return null;
        }
        var draft = drafts.add(id, Drafts.Kind.SHOTS, r.json(), null, "shots-v1");
        drafts.review(draft.id(), "approved", null);
        return r.plan();
    }

    /**
     * The shot's length cut on the frame grid from its start and end, so rounding to whole frames never adds up
     * across shots (each clip would otherwise drift the picture a fraction of a frame further from the voice).
     */
    static double onFrames(ShotPlan.Shot shot, int fps) {
        long first = Math.round(shot.start() * fps);
        long last = Math.round((shot.start() + shot.seconds()) * fps);
        return Math.max(1, last - first) / (double) fps;
    }

    /** Step 3C, with the clip QA from the Stage 3 doc; clips are reused by input hash. */
    private void animate(long storyId, ShotPlan.Shot shot) {
        ShotStore.Image image = store.usable(storyId, shot.id()).orElseThrow();
        AnimationEngine.Camera camera = AnimationEngine.Camera.valueOf(shot.camera().toUpperCase(Locale.ROOT));
        double seconds = onFrames(shot, props.visuals().fps());
        String hash = InputHash.of(animator.version(), image.assetKey(), String.valueOf(seconds),
                camera.name(), String.valueOf(props.visuals().fps()), String.valueOf(props.visuals().outputWidth()));
        if (store.passingClip(hash).filter(c -> assets.exists(c.assetKey())).isPresent()) {
            return;
        }
        String key = "stories/" + storyId + "/shots/" + shot.id() + "/clip.mp4";
        Path tmp = null;
        try {
            tmp = Files.createTempFile("storyforge-clip-", ".mp4");
            AnimationEngine.ClipInfo info = animator.animate(assets.localPath(image.assetKey()).orElseThrow(),
                    seconds, camera, tmp);
            var v = props.visuals();
            double frame = 1.0 / v.fps();
            List<Map<String, Object>> qa = List.of(
                    check("duration", Math.abs(info.seconds() - seconds) <= frame + 1e-6,
                            String.format(Locale.ROOT, "%.3fs for a %.3fs shot", info.seconds(), seconds)),
                    check("format", info.width() == v.outputWidth() && info.height() == v.outputHeight()
                            && Math.abs(info.fps() - v.fps()) < 0.01, info.width() + "x" + info.height() + " @ " + info.fps()),
                    check("frames", !info.blackFrames(), info.blackFrames() ? "black frames found" : "no black frames"),
                    check("motion", true, "window kept inside the image by construction"));
            boolean passed = qa.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
            try (InputStream in = Files.newInputStream(tmp)) {
                assets.put(key, in);
            }
            store.addClip(storyId, shot.id(), hash, key, info.seconds(), ShotPlanner.JSON.writeValueAsString(qa), passed);
            if (!passed) {
                throw new AnimationEngine.AnimationException(shot.id() + " clip failed QA: " + qa, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    private static Map<String, Object> check(String name, boolean pass, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pass", pass);
        m.put("detail", detail);
        return m;
    }

    private void acquireGpu(String holder) {
        Instant deadline = Instant.now().plus(props.images().gpuWait());
        while (!gpu.tryAcquire(holder)) {
            if (Instant.now().isAfter(deadline)) {
                throw new ImageEngineUnavailableException("GPU busy for longer than " + props.images().gpuWait(), null);
            }
            try {
                Thread.sleep(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageEngineUnavailableException("interrupted waiting for the GPU", e);
            }
        }
    }

    private void flag(long storyId, String reason) {
        try {
            status.flag(storyId, StoryStatus.VISUALS_IN_PROGRESS, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("story {} not flagged: {}", storyId, e.getMessage());
        }
    }
}

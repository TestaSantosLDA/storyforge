package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Job;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage1.QaCheck;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage4.Assembler;
import com.storyforge.storage.AssetStorage;
import com.storyforge.storage.InputHash;
import com.storyforge.story.StatusConflictException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders every clip waiting for it, then checks the file. Renders are cached by their inputs, so a retry only redoes
 * what changed. A clip that came out too long goes back to B1 for a shorter slice.
 */
@Component
public class RenderStep {

    static final String TRIGGER = "system:clip-render";
    private static final String VERSION = "reframe-v1";
    private static final Logger log = LoggerFactory.getLogger(RenderStep.class);

    private final ClipJobs jobs;
    private final AssetStorage assets;
    private final StoryforgeProperties props;
    private final ClipRenderer renderer;
    private final Assembler media;

    RenderStep(ClipJobs jobs, AssetStorage assets, StoryforgeProperties props) {
        this.jobs = jobs;
        this.assets = assets;
        this.props = props;
        this.renderer = new ClipRenderer(props);
        this.media = new Assembler(props);
    }

    public void run(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.RENDERING) {
            return;
        }
        Source source = jobs.source(job.sourceVideoId()).orElseThrow();
        try {
            ClipStatus next;
            do {
                for (Clip c : jobs.clips(jobId, ClipStatus.RENDERING)) {
                    if (!render(job, source, c)) {
                        return; // flagged
                    }
                }
                next = jobs.advance(jobId, ClipStatus.RENDERING, TRIGGER);
            } while (next == ClipStatus.RENDERING);
        } catch (Assembler.AssemblyException e) {
            flag(jobId, "Render failed: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during rendering: {}", jobId, e.getMessage());
        }
    }

    private boolean render(Job job, Source source, Clip clip) {
        String credit = job.ownVideo() ? null : "Clip from " + source.title();
        Path closing = closingLine(job);
        String hash = InputHash.of(VERSION, source.contentHash(), String.valueOf(clip.startSec()),
                String.valueOf(clip.endSec()), clip.reframeMode(), String.valueOf(clip.cropOffset()), clip.hookTitle(),
                credit, closing == null ? null : closing.toString(), String.valueOf(props.clips().width()),
                String.valueOf(props.clips().height()));
        String key = "clips/jobs/" + job.id() + "/clip-" + clip.index() + "-" + hash.substring(0, 16) + ".mp4";
        Path dir = props.paths().assetsDir().resolve("clips/tmp/render-" + clip.id()).toAbsolutePath();
        try {
            if (!assets.exists(key)) {
                deleteAll(dir);
                Files.createDirectories(dir);
                Path out = dir.resolve("out.mp4");
                renderer.render(source, assets.localPath(source.fileKey()).orElseThrow(), clip, credit, closing, dir, out);
                try (InputStream in = Files.newInputStream(out)) {
                    assets.put(key, in);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteAll(dir);
        }
        Path file = assets.localPath(key).orElseThrow();
        Assembler.Probe p = media.probe(file);
        List<QaCheck> checks = checks(p, media.loudness(file), media.blackStarts(file),
                ClipRenderer.words(source, clip).size());
        jobs.saveRender(clip.id(), key, p.seconds(), ShotPlanner.JSON.writeValueAsString(checks));
        double limit = props.clips().maxWithClosingLine().toMillis() / 1000.0;
        if (p.seconds() >= limit) {
            jobs.setRework(clip.id(), String.format(Locale.ROOT, "The last pick ran %.1f s with the closing line; "
                    + "pick a shorter one.", p.seconds()));
            jobs.clipStatus(clip, ClipStatus.SELECTING, TRIGGER, "over the length limit");
            return true;
        }
        if (!QaCheck.allPass(checks)) {
            jobs.flag(job.id(), ClipStatus.RENDERING, "Clip " + clip.index() + " failed its checks: "
                    + QaCheck.feedback(checks), TRIGGER);
            return false;
        }
        jobs.clipStatus(clip, ClipStatus.FORMATTING, TRIGGER, "rendered");
        return true;
    }

    /** B2's format, sync, captions, audio and frames rows. Length is handled by the caller. */
    List<QaCheck> checks(Assembler.Probe p, double loudness, List<Double> black, int captionWords) {
        var c = props.clips();
        List<QaCheck> checks = new ArrayList<>();
        checks.add(QaCheck.code("format", p.width() == c.width() && p.height() == c.height()
                        && Math.abs(p.fps() - props.visuals().fps()) < 0.5 && "h264".equals(p.videoCodec())
                        && "aac".equals(p.audioCodec()),
                p.width() + "×" + p.height() + " at " + p.fps() + " fps, " + p.videoCodec() + "/" + p.audioCodec()));
        checks.add(QaCheck.code("captions", captionWords > 0, "no words to caption in this slice"));
        checks.add(QaCheck.code("audio", !Double.isNaN(loudness)
                        && Math.abs(loudness - c.loudnessTarget()) <= c.loudnessTolerance(),
                String.format(Locale.ROOT, "%.1f LUFS; the target is %.1f", loudness, c.loudnessTarget())));
        checks.add(QaCheck.code("frames", black.isEmpty(), "black stretch at " + black + " s"));
        return checks;
    }

    private Path closingLine(Job job) {
        return jobs.channel(job.channel()).map(StoryforgeProperties.Clips.ClipChannel::closingLine)
                .filter(p -> p != null && !p.isBlank()).map(Path::of).filter(Files::isRegularFile).orElse(null);
    }

    private void flag(long jobId, String reason) {
        try {
            jobs.flag(jobId, ClipStatus.RENDERING, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("clip job {} not flagged: {}", jobId, e.getMessage());
        }
    }

    private static void deleteAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("could not clear {}: {}", dir, e.getMessage());
        }
    }
}

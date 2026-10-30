package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Job;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.QaCheck;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage4.Assembler;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.StatusConflictException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** B3: one video, three platform variants that differ in text, hashtags and call to action, with a chosen cover. */
@Component
public class FormatStep {

    static final String TRIGGER = "system:clip-format";
    static final String OWN_CTA = "Full video on our channel";
    private static final Logger log = LoggerFactory.getLogger(FormatStep.class);

    record Post(String caption, List<String> hashtags) {
    }

    record Written(Post youtube, Post tiktok, Post instagram, double coverSecond) {

        Post post(String platform) {
            return switch (platform) {
                case "youtube" -> youtube;
                case "tiktok" -> tiktok;
                default -> instagram;
            };
        }
    }

    private final ClipJobs jobs;
    private final AiCalls ai;
    private final AssetStorage assets;
    private final StoryforgeProperties props;

    FormatStep(ClipJobs jobs, AiCalls ai, AssetStorage assets, StoryforgeProperties props) {
        this.jobs = jobs;
        this.ai = ai;
        this.assets = assets;
        this.props = props;
    }

    public void run(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.FORMATTING) {
            return;
        }
        Source source = jobs.source(job.sourceVideoId()).orElseThrow();
        try {
            ClipStatus next;
            do {
                for (Clip c : jobs.clips(jobId, ClipStatus.FORMATTING)) {
                    if (!format(job, source, c)) {
                        return; // flagged
                    }
                }
                next = jobs.advance(jobId, ClipStatus.FORMATTING, TRIGGER);
            } while (next == ClipStatus.FORMATTING);
        } catch (LlmUnavailableException e) {
            flag(jobId, "Claude unavailable after retries: " + e.getMessage());
        } catch (Assembler.AssemblyException e) {
            flag(jobId, "Cover frame failed: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during formatting: {}", jobId, e.getMessage());
        }
    }

    private boolean format(Job job, Source source, Clip clip) {
        var c = props.clips();
        String transcript = ClipText.spoken(source, clip);
        String feedback = "";
        for (int attempt = 1; attempt <= c.formatTries(); attempt++) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("language", source.language() == null ? "en" : source.language());
            v.put("hook", clip.hookTitle());
            v.put("transcript", transcript);
            v.put("title", source.title());
            v.put("source", job.ownVideo() ? "This is the channel's own video."
                    : "Used with permission: " + job.sourceText());
            v.put("youtube_max", String.valueOf(c.youtubeTitleMax()));
            v.put("youtube_tags", String.valueOf(c.youtubeHashtagsMax()));
            v.put("caption_max", String.valueOf(Math.min(c.tiktokCaptionMax(), c.instagramCaptionMax())));
            v.put("other_tags", String.valueOf(c.otherHashtagsMax()));
            String notes = (clip.reworkNotes() == null ? "" : "\n## Reviewer's notes\n" + clip.reworkNotes() + "\n")
                    + (feedback.isBlank() ? "" : "\n## Fix these problems from the last try\n" + feedback + "\n");
            v.put("notes", notes);
            AiCalls.Outcome o = ai.callForClipJob(job.id(), "b3", "clip_format", "clip-format-v1", "clip-format-v1", v,
                    List.of());
            List<QaCheck> checks = new ArrayList<>();
            Written w = null;
            if (o.ok()) {
                w = ShotPlanner.JSON.readValue(o.json(), Written.class);
                checks.addAll(codeChecks(job, source, clip, w));
            } else {
                checks.add(QaCheck.code("format", false, "no valid answer: " + o.badOutput()));
            }
            String coverKey = null;
            if (QaCheck.allPass(checks)) {
                coverKey = cover(job, clip, w.coverSecond());
                Path cover = assets.localPath(coverKey).orElseThrow();
                Map<String, String> qa = new LinkedHashMap<>();
                qa.put("hook", clip.hookTitle());
                qa.put("transcript", transcript);
                qa.put("variants", describe(w));
                qa.put("cover", cover.toAbsolutePath().toString());
                checks.addAll(ai.judgeForClipJob(job.id(), "b3", "clip_format_qa", "clip-format-qa-v1", qa,
                        List.of(cover)));
            }
            if (QaCheck.allPass(checks)) {
                for (String platform : ClipJobs.PLATFORMS) {
                    Post p = w.post(platform);
                    jobs.saveVariant(clip.id(), platform, p.caption().strip(), String.join(" ", p.hashtags()),
                            cta(job, source), coverKey);
                }
                jobs.setRework(clip.id(), null);
                jobs.clipStatus(clip, ClipStatus.CLIP_READY, TRIGGER, "three variants ready");
                return true;
            }
            feedback = QaCheck.feedback(checks);
            log.info("clip {} format QA failed (try {}): {}", clip.id(), attempt, feedback);
        }
        jobs.flag(job.id(), ClipStatus.FORMATTING, "Clip " + clip.index() + " failed its post text checks: " + feedback,
                TRIGGER);
        return false;
    }

    /** Own videos point to the channel; a permitted source gets its credit and a link to it. */
    static String cta(Job job, Source source) {
        if (job.ownVideo()) {
            return OWN_CTA;
        }
        return "Clip from " + source.title() + (source.url() == null ? "" : " " + source.url());
    }

    List<QaCheck> codeChecks(Job job, Source source, Clip clip, Written w) {
        var c = props.clips();
        List<QaCheck> checks = new ArrayList<>();
        int spare = cta(job, source).length() + 2;
        checks.add(QaCheck.code("caption_youtube", fits(w.youtube(), c.youtubeTitleMax()),
                "the YouTube title must be 1–" + c.youtubeTitleMax() + " characters"));
        checks.add(QaCheck.code("caption_tiktok", fits(w.tiktok(), c.tiktokCaptionMax() - spare),
                "the TikTok caption must leave room for the call to action"));
        checks.add(QaCheck.code("caption_instagram", fits(w.instagram(), c.instagramCaptionMax() - spare),
                "the Instagram caption must leave room for the call to action"));
        checks.add(QaCheck.code("hashtags", w.youtube().hashtags().size() <= c.youtubeHashtagsMax()
                        && w.tiktok().hashtags().size() <= c.otherHashtagsMax()
                        && w.instagram().hashtags().size() <= c.otherHashtagsMax(),
                "at most " + c.youtubeHashtagsMax() + " hashtags on YouTube and " + c.otherHashtagsMax() + " elsewhere"));
        long megabytes = sizeMb(clip.videoKey());
        checks.add(QaCheck.code("platform_spec", clip.durationSec() != null
                        && clip.durationSec() < c.maxWithClosingLine().toSeconds() && megabytes <= c.maxFileMb(),
                megabytes + " MB; the limit is " + c.maxFileMb() + " MB"));
        double length = clip.durationSec() == null ? 0 : clip.durationSec();
        checks.add(QaCheck.code("cover_second", w.coverSecond() >= 0 && w.coverSecond() <= length,
                "cover_second must be inside the clip"));
        return checks;
    }

    private static boolean fits(Post p, int max) {
        return p.caption() != null && !p.caption().isBlank() && p.caption().strip().length() <= max;
    }

    private long sizeMb(String key) {
        try {
            return Files.size(assets.localPath(key).orElseThrow()) / (1024 * 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String cover(Job job, Clip clip, double second) {
        Path dir = props.paths().assetsDir().resolve("clips/tmp").toAbsolutePath();
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("cover-" + clip.id() + ".png");
            Assembler.run(List.of(props.media().ffmpeg(), "-y", "-loglevel", "error",
                    "-ss", String.format(Locale.ROOT, "%.3f", second), "-i",
                    assets.localPath(clip.videoKey()).orElseThrow().toString(), "-frames:v", "1", out.toString()), 120);
            String key = "clips/jobs/" + job.id() + "/cover-" + clip.index() + "-"
                    + Math.round(second * 1000) + ".png";
            try (InputStream in = Files.newInputStream(out)) {
                assets.put(key, in);
            }
            Files.deleteIfExists(out);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(Written w) {
        StringBuilder s = new StringBuilder();
        for (String platform : ClipJobs.PLATFORMS) {
            Post p = w.post(platform);
            s.append("- ").append(platform).append(": ").append(p.caption()).append(' ')
                    .append(String.join(" ", p.hashtags())).append('\n');
        }
        return s.toString();
    }

    private void flag(long jobId, String reason) {
        try {
            jobs.flag(jobId, ClipStatus.FORMATTING, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("clip job {} not flagged: {}", jobId, e.getMessage());
        }
    }
}

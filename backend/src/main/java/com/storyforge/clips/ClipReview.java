package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Job;
import com.storyforge.clips.ClipJobs.Line;
import com.storyforge.clips.ClipJobs.Slice;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage5.Publishing;
import com.storyforge.story.StatusConflictException;
import com.storyforge.validation.InvalidInputException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B4: the only human touchpoint. Approve, reject, kill, skip a platform, record the posts, pick slices by hand. */
@Service
public class ClipReview {

    static final String TRIGGER = "system:clip-review";
    private static final Pattern TIKTOK = Pattern.compile("^https://(www\\.|vm\\.|vt\\.)?tiktok\\.com/\\S+$");
    private static final Pattern INSTAGRAM = Pattern.compile("^https://(www\\.)?instagram\\.com/\\S+$");
    private static final Logger log = LoggerFactory.getLogger(ClipReview.class);

    record Routing(String target, String notes, String hookTitle) {
    }

    private final ClipJobs jobs;
    private final AiCalls ai;
    private final StoryforgeProperties props;

    ClipReview(ClipJobs jobs, AiCalls ai, StoryforgeProperties props) {
        this.jobs = jobs;
        this.ai = ai;
        this.props = props;
    }

    @Transactional
    public void approve(long clipId, String by) {
        Clip c = jobs.clip(clipId);
        need(c, ClipStatus.CLIP_READY);
        jobs.clipStatus(c, ClipStatus.CLIP_APPROVED, by, "approved");
        finishIfDone(jobs.clip(clipId), by);
    }

    /**
     * Rejection needs a reason. Choosing a new framing sends the clip straight to B2; otherwise Claude routes it. The
     * third rejection kills the clip; the job's other clips are unaffected.
     */
    @Transactional
    public void reject(long clipId, String reason, String framing, Double offset, String by) {
        if (reason == null || reason.isBlank()) {
            throw InvalidInputException.of("reason", "Say what's wrong; the reason decides what gets redone.");
        }
        Clip c = jobs.clip(clipId);
        need(c, ClipStatus.CLIP_READY);
        String mode = framing == null || framing.isBlank() ? null : framing;
        if (mode != null && !ClipJobs.REFRAME_MODES.contains(mode)) {
            throw InvalidInputException.of("framing", "Pick a framing.");
        }
        if (offset != null && (offset < 0 || offset > 1)) {
            throw InvalidInputException.of("offset", "The position runs from 0 (left) to 1 (right).");
        }
        jobs.addRejection(clipId, reason.strip());
        int count = jobs.countRejection(clipId);
        if (count >= props.clips().rejections()) {
            jobs.clipStatus(c, ClipStatus.CLIP_KILLED, by, "rejection " + count + ": " + reason.strip());
        } else if (mode != null) {
            jobs.setFraming(clipId, mode, offset);
            jobs.routeRejection(clipId, "render", "new framing: " + mode);
            jobs.clipStatus(c, ClipStatus.RENDERING, by, "rejected with new framing: " + reason.strip());
        } else {
            jobs.clipStatus(c, ClipStatus.ANALYZING_REJECTION, by, "rejected: " + reason.strip());
        }
        nudge(c.jobId(), by);
    }

    @Transactional
    public void kill(long clipId, String by) {
        Clip c = jobs.clip(clipId);
        if (c.status() != ClipStatus.CLIP_READY && c.status() != ClipStatus.CLIP_APPROVED) {
            throw new StatusConflictException("That clip can't be killed now.");
        }
        jobs.clipStatus(c, ClipStatus.CLIP_KILLED, by, "killed");
        nudge(c.jobId(), by);
    }

    @Transactional
    public void skip(long clipId, String platform, String by) {
        Clip c = jobs.clip(clipId);
        if (c.status() != ClipStatus.CLIP_READY && c.status() != ClipStatus.CLIP_APPROVED) {
            throw new StatusConflictException("That clip can't be changed now.");
        }
        jobs.skip(clipId, platform);
        jobs.log(c.jobId(), clipId, c.status(), c.status(), by, "skipped " + platform);
        finishIfDone(jobs.clip(clipId), by);
    }

    @Transactional
    public void markPosted(long clipId, String platform, String url, String by) {
        Clip c = jobs.clip(clipId);
        need(c, ClipStatus.CLIP_APPROVED);
        String u = url == null ? "" : url.strip();
        Pattern valid = switch (platform) {
            case "youtube" -> Publishing.YOUTUBE;
            case "tiktok" -> TIKTOK;
            case "instagram" -> INSTAGRAM;
            default -> throw InvalidInputException.of("platform", "Unknown platform.");
        };
        if (!valid.matcher(u).matches()) {
            throw InvalidInputException.of("url", "That isn't a " + platform + " link.");
        }
        jobs.posted(clipId, platform, u);
        jobs.log(c.jobId(), clipId, c.status(), c.status(), by, "posted on " + platform + ": " + u);
        finishIfDone(jobs.clip(clipId), by);
    }

    /** A clip is complete when every platform is posted or skipped; the job when all its clips are complete or killed. */
    private void finishIfDone(Clip c, String by) {
        if (c.status() == ClipStatus.CLIP_APPROVED && jobs.variants(c.id()).stream().allMatch(ClipJobs.Variant::done)) {
            jobs.clipStatus(c, ClipStatus.CLIP_PUBLISHED, by, "every platform posted or skipped");
            nudge(c.jobId(), by);
        }
    }

    private void nudge(long jobId, String by) {
        if (jobs.get(jobId).status() == ClipStatus.CLIP_READY) {
            jobs.advance(jobId, ClipStatus.CLIP_READY, by);
        }
    }

    /** Status {@code analyzing_rejection}: Claude maps each reason to B1, B2 or B3. */
    public void analyze(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.ANALYZING_REJECTION) {
            return;
        }
        Source source = jobs.source(job.sourceVideoId()).orElseThrow();
        try {
            ClipStatus next;
            do {
                for (Clip c : jobs.clips(jobId, ClipStatus.ANALYZING_REJECTION)) {
                    if (!route(job, source, c)) {
                        return; // flagged
                    }
                }
                next = jobs.advance(jobId, ClipStatus.ANALYZING_REJECTION, TRIGGER);
            } while (next == ClipStatus.ANALYZING_REJECTION);
        } catch (LlmUnavailableException e) {
            flag(jobId, "Claude unavailable after retries: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during analysis: {}", jobId, e.getMessage());
        }
    }

    private boolean route(Job job, Source source, Clip c) {
        String reason = jobs.latestRejection(c.id()).orElse("");
        Map<String, String> v = new LinkedHashMap<>();
        v.put("reason", reason);
        v.put("hook", c.hookTitle());
        v.put("transcript", ClipText.spoken(source, c));
        AiCalls.Outcome o = ai.callForClipJob(job.id(), "b4", "clip_rejection", "clip-rejection-v1",
                "clip-rejection-v1", v, List.of());
        if (!o.ok()) {
            jobs.flag(job.id(), ClipStatus.ANALYZING_REJECTION, "Couldn't work out what to redo: " + o.badOutput(), TRIGGER);
            return false;
        }
        Routing r = ShotPlanner.JSON.readValue(o.json(), Routing.class);
        ClipStatus to = switch (r.target()) {
            case "selection" -> ClipStatus.SELECTING;
            case "format" -> ClipStatus.FORMATTING;
            default -> ClipStatus.RENDERING;
        };
        if (to == ClipStatus.RENDERING) {
            if (r.hookTitle() != null && !r.hookTitle().isBlank()) {
                jobs.setHook(c.id(), r.hookTitle().strip());
            }
            if (!"blurred_fill".equals(c.reframeMode()) && !source.vertical()) {
                jobs.setFraming(c.id(), "blurred_fill", null); // a crop was the problem or a cause: show the whole frame
            }
        }
        jobs.routeRejection(c.id(), r.target(), r.notes());
        jobs.setRework(c.id(), r.notes());
        jobs.clipStatus(c, to, TRIGGER, "routed to " + r.target() + ": " + r.notes());
        return true;
    }

    /** B1 scenario 5 and intake scenario 8: the reviewer picks start and end lines by hand. */
    @Transactional
    public void manualSlice(long jobId, String startLine, String endLine, String hook, String by) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.NEEDS_ATTENTION) {
            throw new StatusConflictException("That job isn't waiting for attention.");
        }
        Source source = jobs.source(job.sourceVideoId()).filter(s -> s.lines() != null)
                .orElseThrow(() -> InvalidInputException.of("startLine", "This video has no transcript yet."));
        List<String> ids = source.lines().stream().map(Line::lineId).toList();
        String a = startLine == null ? "" : startLine.strip().toUpperCase();
        String b = endLine == null ? "" : endLine.strip().toUpperCase();
        if (!ids.contains(a) || !ids.contains(b) || ids.indexOf(b) < ids.indexOf(a)) {
            throw InvalidInputException.of("startLine", "Pick a start and end line from the transcript, in order.");
        }
        if (hook == null || hook.isBlank()) {
            throw InvalidInputException.of("hook", "Give the clip a hook title.");
        }
        Slice s = SelectStep.slice(source, a, b, hook, props.clips());
        double length = s.end() - s.start();
        var c = props.clips();
        if (length < c.minSlice().toSeconds() || length > c.maxSlice().toSeconds()) {
            throw InvalidInputException.of("endLine", "A clip must run " + c.minSlice().toSeconds() + "–"
                    + c.maxSlice().toSeconds() + " s; that one runs " + Math.round(length) + " s.");
        }
        List<Clip> waiting = jobs.clips(jobId, ClipStatus.SELECTING);
        if (waiting.isEmpty()) {
            jobs.addClip(jobId, jobs.clips(jobId).size() + 1, s, job.reframeMode(), by);
        } else {
            jobs.replaceSlice(waiting.getFirst().id(), s, by);
        }
        jobs.transition(jobId, ClipStatus.NEEDS_ATTENTION, ClipStatus.RENDERING, by, "slice picked by hand");
    }

    /** Nothing more will be done for this job. */
    @Transactional
    public void killJob(long jobId, String by) {
        jobs.transition(jobId, ClipStatus.NEEDS_ATTENTION, ClipStatus.CLIP_KILLED, by, "killed");
    }

    private static void need(Clip c, ClipStatus status) {
        if (c.status() != status) {
            throw new StatusConflictException("That clip is " + c.status().dbValue().replace('_', ' ') + " now.");
        }
    }

    private void flag(long jobId, String reason) {
        try {
            jobs.flag(jobId, ClipStatus.ANALYZING_REJECTION, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("clip job {} not flagged: {}", jobId, e.getMessage());
        }
    }
}

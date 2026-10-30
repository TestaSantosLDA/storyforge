package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Job;
import com.storyforge.clips.ClipJobs.Line;
import com.storyforge.clips.ClipJobs.Slice;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.QaCheck;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.story.StatusConflictException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** B1 on an intake manifest: Claude picks slices, code and an AI judge check them, slices snap to scene cuts. */
@Component
public class SelectStep {

    static final String TRIGGER = "system:clip-select";
    private static final String SPOILER_RULE = "Don't spoil: the ending and the main payoff stay in the full video.";
    private static final Logger log = LoggerFactory.getLogger(SelectStep.class);

    record Picked(List<Pick> clips) {
        record Pick(String startLine, String endLine, String hookTitle) {
        }
    }

    private final ClipJobs jobs;
    private final AiCalls ai;
    private final StoryforgeProperties props;
    private final TransactionTemplate tx;

    SelectStep(ClipJobs jobs, AiCalls ai, StoryforgeProperties props, TransactionTemplate tx) {
        this.jobs = jobs;
        this.ai = ai;
        this.props = props;
        this.tx = tx;
    }

    public void run(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.SELECTING) {
            return;
        }
        Source source = jobs.source(job.sourceVideoId()).orElseThrow();
        try {
            ClipStatus next;
            do {
                List<Clip> redo = jobs.clips(jobId, ClipStatus.SELECTING);
                if (!jobs.clips(jobId).isEmpty() && redo.isEmpty()) {
                    next = jobs.advance(jobId, ClipStatus.SELECTING, TRIGGER);
                    continue;
                }
                if (!select(job, source, redo)) {
                    return; // flagged
                }
                next = jobs.advance(jobId, ClipStatus.SELECTING, TRIGGER);
            } while (next == ClipStatus.SELECTING);
        } catch (LlmUnavailableException e) {
            flag(jobId, "Claude unavailable after retries: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during selection: {}", jobId, e.getMessage());
        }
    }

    /** First pick for a new job, or a new moment for each rejected clip. */
    private boolean select(Job job, Source source, List<Clip> redo) {
        int count = redo.isEmpty() ? job.targetClipCount() : redo.size();
        List<Clip> keep = jobs.clips(job.id()).stream()
                .filter(c -> c.status() != ClipStatus.SELECTING && c.status() != ClipStatus.CLIP_KILLED).toList();
        String feedback = "";
        for (int attempt = 1; attempt <= props.clips().selectionTries(); attempt++) {
            AiCalls.Outcome o = ai.callForClipJob(job.id(), "b1", "clip_select", "clip-select-v1", "clip-select-v1",
                    vars(job, source, count, redo, keep, feedback), List.of());
            List<QaCheck> checks = new ArrayList<>();
            List<Picked.Pick> picks = List.of();
            if (o.ok()) {
                picks = ShotPlanner.JSON.readValue(o.json(), Picked.class).clips();
                checks.addAll(codeChecks(job, source, picks, count, keep));
            } else {
                checks.add(QaCheck.code("format", false, "no valid answer: " + o.badOutput()));
            }
            if (QaCheck.allPass(checks)) {
                Map<String, String> qa = new LinkedHashMap<>();
                qa.put("title", source.title());
                qa.put("lines", lines(source));
                qa.put("clips", describe(source, picks));
                qa.put("spoilers", job.spoilersAllowed() ? "pass; the reviewer allowed spoilers."
                        : "no clip contains the video's ending or main payoff.");
                checks.addAll(ai.judgeForClipJob(job.id(), "b1", "clip_select_qa", "clip-select-qa-v1", qa, List.of()));
            }
            if (QaCheck.allPass(checks)) {
                save(job, source, picks, redo);
                return true;
            }
            feedback = QaCheck.feedback(checks);
            log.info("clip job {} selection QA failed (try {}): {}", job.id(), attempt, feedback);
        }
        jobs.flag(job.id(), ClipStatus.SELECTING, "No valid clips after " + props.clips().selectionTries()
                + " tries: " + feedback + " You can pick the lines by hand.", TRIGGER);
        return false;
    }

    private Map<String, String> vars(Job job, Source source, int count, List<Clip> redo, List<Clip> keep,
            String feedback) {
        var c = props.clips();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("count", String.valueOf(count));
        v.put("title", source.title());
        v.put("lines", lines(source));
        v.put("min_seconds", String.valueOf(c.minSlice().toSeconds()));
        v.put("max_seconds", String.valueOf(props.clips().maxSlice().toSeconds()));
        v.put("spoilers", job.spoilersAllowed() ? "Spoilers are fine in this job." : SPOILER_RULE);
        v.put("taken", keep.isEmpty() ? "" : " Already used, so stay clear of them: " + keep.stream()
                .map(k -> k.startLineId() + "–" + k.endLineId()).collect(Collectors.joining(", ")) + ".");
        StringBuilder notes = new StringBuilder();
        if (job.notes() != null) {
            notes.append("\n## The person's notes\n").append(job.notes()).append('\n');
        }
        redo.stream().filter(r -> r.reworkNotes() != null).forEach(r -> notes.append("\n## Reviewer rejected the clip ")
                .append(r.startLineId()).append("–").append(r.endLineId()).append('\n').append(r.reworkNotes()).append('\n'));
        if (!feedback.isBlank()) {
            notes.append("\n## Fix these problems from the last try\n").append(feedback).append('\n');
        }
        v.put("notes", notes.toString());
        return v;
    }

    /** The B1 QA rows code can decide: boundaries, length, overlap. */
    List<QaCheck> codeChecks(Job job, Source source, List<Picked.Pick> picks, int count, List<Clip> keep) {
        Map<String, Integer> index = new LinkedHashMap<>();
        source.lines().forEach(l -> index.put(l.lineId(), index.size()));
        List<QaCheck> checks = new ArrayList<>();
        checks.add(QaCheck.code("count", !picks.isEmpty() && picks.size() <= count,
                picks.size() + " clips; give between 1 and " + count));
        List<int[]> taken = new ArrayList<>();
        keep.forEach(k -> taken.add(new int[] {index.getOrDefault(k.startLineId(), -1),
                index.getOrDefault(k.endLineId(), -1)}));
        for (Picked.Pick p : picks) {
            Integer a = index.get(p.startLine());
            Integer b = index.get(p.endLine());
            String name = p.startLine() + "–" + p.endLine();
            if (a == null || b == null || b < a) {
                checks.add(QaCheck.code("boundaries", false, name + " aren't lines of the transcript, in order"));
                continue;
            }
            double len = source.lines().get(b).end() - source.lines().get(a).start();
            long max = props.clips().maxSlice().toSeconds();
            checks.add(QaCheck.code("length", len >= props.clips().minSlice().toSeconds() && len <= max,
                    String.format(Locale.ROOT, "%s runs %.1f s; keep it within %d–%d s", name, len,
                            props.clips().minSlice().toSeconds(), max)));
            boolean clear = taken.stream().noneMatch(t -> a <= t[1] && t[0] <= b);
            checks.add(QaCheck.code("overlap", clear, name + " shares lines with another clip"));
            taken.add(new int[] {a, b});
            checks.add(QaCheck.code("hook_title", p.hookTitle() != null && !p.hookTitle().isBlank()
                    && p.hookTitle().length() <= 60, name + " needs a hook title of at most 60 characters"));
        }
        return checks;
    }

    private void save(Job job, Source source, List<Picked.Pick> picks, List<Clip> redo) {
        tx.executeWithoutResult(t -> {
            int i = 0;
            for (Picked.Pick p : picks) {
                Slice s = slice(source, p.startLine(), p.endLine(), p.hookTitle(), props.clips());
                if (i < redo.size()) {
                    jobs.replaceSlice(redo.get(i).id(), s, TRIGGER);
                } else {
                    jobs.addClip(job.id(), jobs.clips(job.id()).size() + 1, s, job.reframeMode(), TRIGGER);
                }
                i++;
            }
            if (picks.size() < job.targetClipCount() && redo.isEmpty()) {
                jobs.log(job.id(), null, ClipStatus.SELECTING, ClipStatus.SELECTING, TRIGGER,
                        "accepted " + picks.size() + " of " + job.targetClipCount() + " clips");
            }
        });
    }

    static Slice slice(Source source, String startLine, String endLine, String hook, StoryforgeProperties.Clips c) {
        Line first = source.lines().stream().filter(l -> l.lineId().equals(startLine)).findFirst().orElseThrow();
        Line last = source.lines().stream().filter(l -> l.lineId().equals(endLine)).findFirst().orElseThrow();
        double reach = c.sceneSnap().toMillis() / 1000.0;
        List<Double> cuts = source.sceneCuts() == null ? List.of() : source.sceneCuts();
        return new Slice(startLine, endLine, Transcript.snap(first.start(), cuts, reach),
                Transcript.snap(last.end(), cuts, reach), hook.strip());
    }

    private static String lines(Source s) {
        return s.lines().stream().map(l -> String.format(Locale.ROOT, "%s [%.1f–%.1f] %s", l.lineId(), l.start(), l.end(),
                l.text())).collect(Collectors.joining("\n"));
    }

    private static String describe(Source s, List<Picked.Pick> picks) {
        return picks.stream().map(p -> "- \"" + p.hookTitle() + "\": " + p.startLine() + "–" + p.endLine() + ": "
                + ClipText.spoken(s, p.startLine(), p.endLine())).collect(Collectors.joining("\n"));
    }

    private void flag(long jobId, String reason) {
        try {
            jobs.flag(jobId, ClipStatus.SELECTING, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("clip job {} not flagged: {}", jobId, e.getMessage());
        }
    }
}

package com.storyforge.stage4;

import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.notes.StoryNotes;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage2.Pronunciation;
import com.storyforge.stage3.ShotPlan;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage3.ShotStore;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.StoryCounter;
import com.storyforge.story.StoryCounters;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.validation.InvalidInputException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The final review gate (Approve, Reject with reason, Kill) and the rejection analysis: Claude turns the reason into
 * a correction plan, and only the named shots, lines, music or subtitles are redone before the video comes back.
 */
@Service
public class FinalReview {

    static final String TRIGGER = "system:stage4-review";
    private static final Logger log = LoggerFactory.getLogger(FinalReview.class);

    public record Target(String type, List<String> ids, String instruction, String word, String sayAs) {
    }

    public record CorrectionPlan(boolean confident, String summary, List<Target> targets) {
    }

    public record Rejection(long id, String reason, String plan, String outcome) {
    }

    private final StoryStatusService status;
    private final StoryCounters counters;
    private final Drafts drafts;
    private final AudioLines audio;
    private final ShotStore shots;
    private final Pronunciation pronunciation;
    private final StoryNotes notes;
    private final AiCalls ai;
    private final JdbcClient jdbc;

    FinalReview(StoryStatusService status, StoryCounters counters, Drafts drafts, AudioLines audio, ShotStore shots,
            Pronunciation pronunciation, StoryNotes notes, AiCalls ai, JdbcClient jdbc) {
        this.status = status;
        this.counters = counters;
        this.drafts = drafts;
        this.audio = audio;
        this.shots = shots;
        this.pronunciation = pronunciation;
        this.notes = notes;
        this.ai = ai;
        this.jdbc = jdbc;
    }

    @Transactional
    public void approve(long storyId, String by) {
        status.transition(storyId, StoryStatus.AWAITING_FINAL_REVIEW, StoryStatus.FINAL_APPROVED, by, "approved");
        // Stage 5 starts automatically.
        status.handOff(storyId, StoryStatus.FINAL_APPROVED, TRIGGER, "preparing the publish package");
    }

    @Transactional
    public void kill(long storyId, String by) {
        status.archive(storyId, StoryStatus.AWAITING_FINAL_REVIEW, ArchiveReason.KILLED, by, "killed at final review");
    }

    /** Reject with a required reason; the analysis runs next. The third rejection stops for a person (not archived). */
    @Transactional
    public void reject(long storyId, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw InvalidInputException.of("reason", "Say what's wrong; the reason decides what gets redone.");
        }
        Long assemblyId = jdbc.sql("select id from assembly where story_id = :s order by version desc limit 1")
                .param("s", storyId).query(Long.class).optional().orElse(null);
        jdbc.sql("insert into final_rejection (story_id, assembly_id, reason) values (:s, :a, :r)")
                .param("s", storyId).param("a", assemblyId).param("r", reason.strip()).update();
        StoryCounters.Count count = counters.increment(storyId, StoryCounter.FINAL_REJECTIONS);
        status.transition(storyId, StoryStatus.AWAITING_FINAL_REVIEW, StoryStatus.ANALYZING_REJECTION, by,
                "rejected: " + reason.strip());
        if (count.reachedLimit()) {
            status.flag(storyId, StoryStatus.ANALYZING_REJECTION, "Third final rejection. Resume to apply this one, "
                    + "or Kill the story.", by);
        }
    }

    public Optional<Rejection> latestRejection(long storyId) {
        return jdbc.sql("select * from final_rejection where story_id = :s order by id desc limit 1")
                .param("s", storyId).query((rs, n) -> new Rejection(rs.getLong("id"), rs.getString("reason"),
                        rs.getString("plan"), rs.getString("outcome"))).optional();
    }

    /** The analysis step (status {@code analyzing_rejection}). */
    public void analyze(long storyId) {
        Rejection rejection = latestRejection(storyId).orElse(null);
        if (rejection == null) {
            flag(storyId, "No rejection to analyse.");
            return;
        }
        CorrectionPlan plan;
        try {
            plan = plan(storyId, rejection.reason());
        } catch (LlmUnavailableException e) {
            flag(storyId, "Claude unavailable after retries: " + e.getMessage());
            return;
        }
        if (plan == null || !plan.confident() || plan.targets().isEmpty()) {
            record(rejection.id(), plan, "not mapped");
            flag(storyId, "The rejection couldn't be mapped to specific fixes with confidence. Reword it more "
                    + "specifically (shot, line or time), or tell me what to redo.");
            return;
        }
        try {
            StoryStatus next = apply(storyId, rejection.reason(), plan);
            record(rejection.id(), plan, "routed to " + next.dbValue());
            status.transition(storyId, StoryStatus.ANALYZING_REJECTION, next, TRIGGER, plan.summary());
        } catch (StatusConflictException e) {
            log.info("story {} moved on during analysis: {}", storyId, e.getMessage());
        }
    }

    CorrectionPlan plan(long storyId, String reason) {
        AudioLines.Track track = audio.track(storyId).orElseThrow();
        double intro = jdbc.sql("select intro_sec from assembly where story_id = :s order by version desc limit 1")
                .param("s", storyId).query(java.math.BigDecimal.class).optional().map(java.math.BigDecimal::doubleValue)
                .orElse(0.0);
        ScriptContent script = ShotPlanner.JSON.readValue(
                drafts.approved(storyId, Drafts.Kind.SCRIPT).orElseThrow().content(), ScriptContent.class);
        Map<String, double[]> timing = ShotPlanner.timing(track.manifest());
        ShotPlan shotPlan = ShotPlanner.time(ShotPlanner.JSON.readValue(
                drafts.approved(storyId, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class),
                timing, track.durationSec());
        Map<String, String> sceneOf = new LinkedHashMap<>();
        script.scenes().forEach(sc -> sc.lineIds().forEach(id -> sceneOf.put(id, sc.id())));
        String shotsText = shotPlan.shots().stream().map(s -> String.format(Locale.ROOT, "- %s (%s) %s–%s: %s %s",
                s.id(), s.sceneId(), clock(intro + s.start()), clock(intro + s.end()), s.setting(),
                s.actions().stream().map(ShotPlan.Action::action).collect(Collectors.joining(" "))))
                .collect(Collectors.joining("\n"));
        String linesText = script.lines().stream().map(l -> String.format(Locale.ROOT, "- %s (%s, %s) %s: %s", l.id(),
                sceneOf.get(l.id()), l.speaker(), clock(intro + timing.get(l.id())[0]), l.text()))
                .collect(Collectors.joining("\n"));
        String musicText = jdbc.sql("select music from assembly where story_id = :s order by version desc limit 1")
                .param("s", storyId).query(String.class).optional().map(json -> {
                    List<String> rows = new ArrayList<>();
                    for (JsonNode m : ShotPlanner.JSON.readTree(json)) {
                        rows.add("- " + m.path("title").asString() + " under scenes " + m.path("scenes") + " "
                                + clock(m.path("start").asDouble()) + "–" + clock(m.path("end").asDouble()));
                    }
                    return rows.isEmpty() ? "(no music)" : String.join("\n", rows);
                }).orElse("(no music)");
        AiCalls.Outcome o = ai.call(storyId, "stage4", "rejection_analysis", "rejection-v1", "rejection-v1", Map.of(
                "reason", reason, "intro_end", clock(intro), "shots", shotsText, "lines", linesText, "music", musicText));
        return o.ok() ? ShotPlanner.JSON.readValue(o.json(), CorrectionPlan.class) : null;
    }

    /** Applies every target and returns the earliest stage that has to run again. */
    StoryStatus apply(long storyId, String reason, CorrectionPlan plan) {
        Set<StoryStatus> stages = new LinkedHashSet<>();
        for (Target t : plan.targets()) {
            switch (t.type()) {
                case "story" -> {
                    drafts.approved(storyId, Drafts.Kind.STORY).ifPresent(d -> drafts.review(d.id(), "rejected",
                            "Final review: " + reason + " " + t.instruction()));
                    stages.add(StoryStatus.STORY_IN_PROGRESS);
                }
                case "script" -> {
                    drafts.approved(storyId, Drafts.Kind.SCRIPT).ifPresent(d -> drafts.review(d.id(), "rejected",
                            "Final review: " + reason + " " + t.instruction()));
                    stages.add(StoryStatus.SCRIPT_IN_PROGRESS);
                }
                case "audio_line" -> {
                    if (t.word() != null && !t.word().isBlank() && t.sayAs() != null && !t.sayAs().isBlank()) {
                        pronunciation.put(t.word(), t.sayAs());
                    }
                    audio.supersede(storyId, t.ids());
                    stages.add(StoryStatus.AUDIO_IN_PROGRESS);
                }
                case "shot" -> {
                    t.ids().forEach(id -> notes.put(storyId, "shot:" + id, t.instruction()));
                    shots.supersede(storyId, t.ids());
                    stages.add(StoryStatus.VISUALS_IN_PROGRESS);
                }
                case "mix" -> {
                    double now = notes.get(storyId, "mix").map(Double::parseDouble).orElse(-18.0);
                    boolean louder = t.instruction().toLowerCase(Locale.ROOT).contains("louder");
                    notes.put(storyId, "mix", String.valueOf(now + (louder ? 4 : -6)));
                    stages.add(StoryStatus.ASSEMBLING);
                }
                case "music" -> {
                    String used = jdbc.sql("select music from assembly where story_id = :s order by version desc limit 1")
                            .param("s", storyId).query(String.class).optional().orElse("[]");
                    Set<String> exclude = new LinkedHashSet<>(List.of(notes.get(storyId, "music-exclude").orElse("")
                            .split(",")));
                    for (JsonNode m : ShotPlanner.JSON.readTree(used)) {
                        for (JsonNode sc : m.path("scenes")) {
                            if (t.ids().contains(sc.asString()) || t.ids().isEmpty()) {
                                exclude.add(m.path("track_id").asString());
                            }
                        }
                    }
                    exclude.remove("");
                    notes.put(storyId, "music-exclude", String.join(",", exclude));
                    stages.add(StoryStatus.ASSEMBLING);
                }
                default -> stages.add(StoryStatus.ASSEMBLING); // subtitles are rebuilt on every assembly
            }
        }
        for (StoryStatus s : List.of(StoryStatus.STORY_IN_PROGRESS, StoryStatus.SCRIPT_IN_PROGRESS,
                StoryStatus.AUDIO_IN_PROGRESS, StoryStatus.VISUALS_IN_PROGRESS)) {
            if (stages.contains(s)) {
                return s;
            }
        }
        return StoryStatus.ASSEMBLING;
    }

    private void record(long rejectionId, CorrectionPlan plan, String outcome) {
        jdbc.sql("update final_rejection set plan = cast(:p as jsonb), outcome = :o where id = :id")
                .param("p", plan == null ? null : ShotPlanner.JSON.writeValueAsString(plan)).param("o", outcome)
                .param("id", rejectionId).update();
    }

    private void flag(long storyId, String reason) {
        try {
            status.flag(storyId, StoryStatus.ANALYZING_REJECTION, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("story {} not flagged: {}", storyId, e.getMessage());
        }
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}

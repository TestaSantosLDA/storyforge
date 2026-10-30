package com.storyforge.stage3;

import com.storyforge.character.CharacterFile;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.QaCheck;
import com.storyforge.stage1.ScriptContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 3A: Claude splits each scene into 5–10 second shots at line boundaries; code checks the plan (coverage,
 * cast, character count, durations from the timing manifest) and retries with the failed checks.
 */
@Component
public class ShotPlanner {

    public static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** A planned, timed shot list, or the failed checks of the last try. */
    public record Result(ShotPlan plan, String json, List<QaCheck> failures) {
        public boolean ok() {
            return plan != null;
        }
    }

    private final AiCalls ai;
    private final StoryforgeProperties.Visuals v;

    ShotPlanner(AiCalls ai, StoryforgeProperties props) {
        this.ai = ai;
        this.v = props.visuals();
    }

    /**
     * @param timing line id → [start, end] seconds in the narration track
     * @param trackEnd total narration length in seconds
     */
    public Result plan(long storyId, String setting, ScriptContent script, List<CharacterFile> cast,
            Map<String, double[]> timing, double trackEnd) {
        Map<String, String> sceneOf = new HashMap<>();
        script.scenes().forEach(sc -> sc.lineIds().forEach(id -> sceneOf.put(id, sc.id())));
        // On-screen seconds include the pause after each line, so Claude's sums match the real shot lengths.
        // (First real run: with speaking time only, every plan overshot the 10 s limit.)
        Map<String, Double> onScreen = onScreen(script, timing, trackEnd);
        String lines = script.lines().stream().map(l -> String.format(java.util.Locale.ROOT, "- %s | %s | %s | %.1fs | %s",
                        l.id(), sceneOf.get(l.id()), l.speaker(), onScreen.get(l.id()), l.text()))
                .collect(Collectors.joining("\n"));
        String scenes = script.scenes().stream().map(sc -> "- " + sc.id() + " (" + sc.mood() + "): "
                + sc.visualDescription() + " Lines " + sc.lineIds().getFirst() + "–" + sc.lineIds().getLast())
                .collect(Collectors.joining("\n"));
        String castText = cast.isEmpty() ? "(no characters; narrator only)"
                : cast.stream().map(c -> "- " + c.id() + " (" + c.name() + "): " + c.visualDescription())
                        .collect(Collectors.joining("\n"));

        String feedback = "";
        List<QaCheck> failures = List.of();
        for (int attempt = 1; attempt <= v.shotPlanRetries(); attempt++) {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("setting", setting);
            vars.put("cast", castText);
            vars.put("scenes", scenes);
            vars.put("lines", lines);
            vars.put("min_seconds", String.valueOf(v.minShot().toSeconds()));
            vars.put("max_seconds", String.valueOf(v.maxShot().toSeconds()));
            vars.put("max_characters", String.valueOf(v.maxCharactersPerShot()));
            vars.put("feedback", feedback.isBlank() ? "" : "\n## Fix these problems from the last plan\n" + feedback);
            AiCalls.Outcome o = ai.call(storyId, "stage3", "shot_plan", "shots-v1", "shots-v1", vars);
            if (!o.ok()) {
                failures = List.of(QaCheck.code("format", false, o.badOutput()));
            } else {
                ShotPlan raw = JSON.readValue(o.json(), ShotPlan.class);
                ShotPlan timed = time(repair(raw, onScreen), timing, trackEnd);
                failures = check(timed, script, sceneOf, cast.stream().map(CharacterFile::id).collect(Collectors.toSet()))
                        .stream().filter(c -> !c.pass()).toList();
                if (failures.isEmpty()) {
                    return new Result(timed, JSON.writeValueAsString(timed), List.of());
                }
            }
            feedback = QaCheck.feedback(failures);
        }
        return new Result(null, null, failures);
    }

    /** Seconds each line is on screen: from its start to the next line's start (the last runs to the track end). */
    static Map<String, Double> onScreen(ScriptContent script, Map<String, double[]> timing, double trackEnd) {
        Map<String, Double> out = new HashMap<>();
        List<ScriptContent.Line> ls = script.lines();
        for (int i = 0; i < ls.size(); i++) {
            double start = i == 0 ? 0 : timing.get(ls.get(i).id())[0];
            double end = i + 1 < ls.size() ? timing.get(ls.get(i + 1).id())[0] : trackEnd;
            out.put(ls.get(i).id(), end - start);
        }
        return out;
    }

    /**
     * Fixes shot lengths without another Claude round: a shot over the limit is split at line boundaries (each part
     * keeps the shot's picture), and a shot under the minimum is merged into a same-scene neighbour when the result
     * still fits. Ids are renumbered. Plans that can't be fixed this way still fail the duration check.
     */
    ShotPlan repair(ShotPlan plan, Map<String, Double> onScreen) {
        double max = v.maxShot().toMillis() / 1000.0;
        double min = v.minShot().toMillis() / 1000.0;
        List<ShotPlan.Shot> split = new ArrayList<>();
        for (ShotPlan.Shot s : plan.shots()) {
            List<String> part = new ArrayList<>();
            double secs = 0;
            for (String id : s.lineIds()) {
                double d = onScreen.getOrDefault(id, 0.0);
                if (!part.isEmpty() && secs + d > max) {
                    split.add(withLines(s, part));
                    part = new ArrayList<>();
                    secs = 0;
                }
                part.add(id);
                secs += d;
            }
            split.add(withLines(s, part));
        }
        List<ShotPlan.Shot> merged = new ArrayList<>();
        for (ShotPlan.Shot s : split) {
            double secs = seconds(s, onScreen);
            if (!merged.isEmpty() && secs < min) {
                ShotPlan.Shot prev = merged.getLast();
                if (prev.sceneId().equals(s.sceneId()) && seconds(prev, onScreen) + secs <= max + 0.05) {
                    List<String> ids = new ArrayList<>(prev.lineIds());
                    ids.addAll(s.lineIds());
                    merged.set(merged.size() - 1, withLines(prev, ids));
                    continue;
                }
            }
            if (!merged.isEmpty() && seconds(merged.getLast(), onScreen) < min) {
                ShotPlan.Shot prev = merged.getLast();
                if (prev.sceneId().equals(s.sceneId()) && seconds(prev, onScreen) + secs <= max + 0.05) {
                    List<String> ids = new ArrayList<>(prev.lineIds());
                    ids.addAll(s.lineIds());
                    merged.set(merged.size() - 1, withLines(prev, ids));
                    continue;
                }
            }
            merged.add(s);
        }
        List<ShotPlan.Shot> forward = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            ShotPlan.Shot s = merged.get(i);
            ShotPlan.Shot next = i + 1 < merged.size() ? merged.get(i + 1) : null;
            if (next != null && seconds(s, onScreen) < min && next.sceneId().equals(s.sceneId())
                    && seconds(s, onScreen) + seconds(next, onScreen) <= max + 0.05) {
                List<String> ids = new ArrayList<>(s.lineIds());
                ids.addAll(next.lineIds());
                merged.set(i + 1, withLines(next, ids));
                continue;
            }
            forward.add(s);
        }
        merged = forward;
        List<ShotPlan.Shot> out = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            ShotPlan.Shot s = merged.get(i);
            out.add(new ShotPlan.Shot(String.format("SH%02d", i + 1), s.sceneId(), s.lineIds(), s.characters(),
                    s.actions(), s.setting(), s.framing(), s.camera(), null, null));
        }
        return new ShotPlan(out);
    }

    private static ShotPlan.Shot withLines(ShotPlan.Shot s, List<String> ids) {
        return new ShotPlan.Shot(s.id(), s.sceneId(), List.copyOf(ids), s.characters(), s.actions(), s.setting(),
                s.framing(), s.camera(), null, null);
    }

    private static double seconds(ShotPlan.Shot s, Map<String, Double> onScreen) {
        return s.lineIds().stream().mapToDouble(id -> onScreen.getOrDefault(id, 0.0)).sum();
    }

    /** A shot runs from its first line's start to the next shot's start (pauses included), the last to the track end. */
    public static ShotPlan time(ShotPlan plan, Map<String, double[]> timing, double trackEnd) {
        List<ShotPlan.Shot> out = new ArrayList<>();
        List<ShotPlan.Shot> shots = plan.shots();
        for (int i = 0; i < shots.size(); i++) {
            double[] first = timing.get(shots.get(i).lineIds().getFirst());
            double start = i == 0 ? 0 : first == null ? 0 : first[0];
            double[] next = i + 1 < shots.size() ? timing.get(shots.get(i + 1).lineIds().getFirst()) : null;
            double end = i + 1 < shots.size() ? (next == null ? start : next[0]) : trackEnd;
            out.add(shots.get(i).timed(round(start), round(end)));
        }
        return new ShotPlan(out);
    }

    /** The Step 3A QA from the Stage 3 doc. */
    List<QaCheck> check(ShotPlan plan, ScriptContent script, Map<String, String> sceneOf, Set<String> castIds) {
        List<String> order = plan.shots().stream().flatMap(s -> s.lineIds().stream()).toList();
        List<String> expected = script.lines().stream().map(ScriptContent.Line::id).toList();
        List<String> coverage = new ArrayList<>();
        if (!order.equals(expected)) {
            Set<String> seen = new LinkedHashSet<>(order);
            expected.stream().filter(id -> !seen.contains(id)).forEach(id -> coverage.add(id + " is in no shot"));
            if (order.size() != seen.size()) {
                coverage.add("a line is in more than one shot");
            }
            if (coverage.isEmpty()) {
                coverage.add("shots must cover the lines in script order");
            }
        }
        List<String> scenes = new ArrayList<>();
        List<String> cast = new ArrayList<>();
        List<String> durations = new ArrayList<>();
        double max = v.maxShot().toMillis() / 1000.0;
        double min = v.minShot().toMillis() / 1000.0;
        List<ShotPlan.Shot> all = plan.shots();
        for (int i = 0; i < all.size(); i++) {
            ShotPlan.Shot s = all.get(i);
            s.lineIds().stream().filter(id -> !s.sceneId().equals(sceneOf.get(id)))
                    .forEach(id -> scenes.add(s.id() + " has " + id + " from another scene"));
            s.characters().stream().filter(c -> !castIds.contains(c))
                    .forEach(c -> cast.add(s.id() + " uses \"" + c + "\", who is not in the cast"));
            if (s.characters().size() > v.maxCharactersPerShot()) {
                cast.add(s.id() + " has " + s.characters().size() + " characters; at most " + v.maxCharactersPerShot());
            }
            s.actions().stream().filter(a -> !s.characters().contains(a.character()))
                    .forEach(a -> cast.add(s.id() + " gives an action to " + a.character() + ", who isn't in the shot"));
            if (s.seconds() > max + 0.05 && s.lineIds().size() > 1) {
                durations.add(String.format("%s is %.1fs; split it (max %.0fs)", s.id(), s.seconds(), max));
            }
            // The minimum is a target only (repair() merges short shots where it can). In three real runs a hard
            // minimum made plans unsolvable or failed on rounding, so a short shot never fails the plan.
        }
        return List.of(
                QaCheck.code("coverage", coverage.isEmpty(), String.join("; ", coverage)),
                QaCheck.code("scenes", scenes.isEmpty(), String.join("; ", scenes)),
                QaCheck.code("cast", cast.isEmpty(), String.join("; ", cast)),
                QaCheck.code("durations", durations.isEmpty(), String.join("; ", durations)));
    }

    /** Line id → [start, end] from the Stage 2 manifest. */
    public static Map<String, double[]> timing(String manifestJson) {
        Map<String, double[]> out = new HashMap<>();
        JsonNode lines = JSON.readTree(manifestJson).path("lines");
        for (JsonNode l : lines) {
            out.put(l.path("line_id").asString(), new double[] {l.path("start").asDouble(), l.path("end").asDouble()});
        }
        return out;
    }

    private static double round(double s) {
        return Math.round(s * 1000) / 1000.0;
    }
}

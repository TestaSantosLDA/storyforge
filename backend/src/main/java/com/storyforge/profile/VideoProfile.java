package com.storyforge.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One video profile (docs/pipeline-d/README.md "Video profiles"): a versioned YAML file in
 * {@code channel/video-profiles/}, picked per topic with overrides. {@link #parse} checks every field and reports all
 * problems at once.
 */
public record VideoProfile(
        String id,
        int version,
        String genre,
        String tone,
        String facts,
        String structure,
        String visualStyle,
        String voices,
        String narratorVoice,
        String frame,
        Range targetSeconds,
        Range beatSeconds,
        String motion,
        String onScreenText,
        String illustrations,
        String opening,
        boolean music,
        List<String> musicMood,
        String audience,
        String lookNotes) {

    public static final Set<String> GENRES = Set.of("explanatory", "entertaining", "historic", "inspirational",
            "mystery");
    public static final Set<String> FACTS = Set.of("fiction", "checked");
    public static final Set<String> VOICES = Set.of("narrator_only", "cast");
    public static final Set<String> FRAMES = Set.of("vertical", "landscape");
    public static final Set<String> MOTIONS = Set.of("calm", "snappy", "documentary");
    public static final Set<String> ILLUSTRATIONS = Set.of("flux", "none");
    public static final Set<String> OPENINGS = Set.of("none", "title_sting");
    public static final Set<String> AUDIENCES = Set.of("not_made_for_kids", "made_for_kids");

    /** Keys a topic may override; {@code id} and {@code version} belong to the file. */
    public static final Set<String> OVERRIDABLE = Set.of("genre", "tone", "facts", "structure", "visual_style",
            "voices", "narrator_voice", "frame", "target_seconds", "beat_seconds", "motion", "on_screen_text",
            "illustrations", "opening", "music", "music_mood", "audience", "look_notes");

    /** Text fields Claude reads, checked for names that must never appear in prompts. */
    static final List<String> PROMPT_TEXT = List.of("tone", "structure", "visual_style", "look_notes");

    public record Range(double target, double min, double max) {
    }

    /** Length limits that come with the frame (Pipeline D "Frames and lengths"). */
    public record Limits(int verticalMaxSeconds, int landscapeMinSeconds) {
    }

    public boolean landscape() {
        return "landscape".equals(frame);
    }

    /** Builds a profile from raw YAML fields, or throws with every problem found. */
    public static VideoProfile parse(Map<String, Object> raw, Limits limits) {
        Fields f = new Fields(raw);
        String frame = f.oneOf("frame", FRAMES, "vertical");
        Range target = f.range("target_seconds", true);
        Range beat = f.range("beat_seconds", false);
        if (target != null) {
            if ("vertical".equals(frame) && target.max() > limits.verticalMaxSeconds()) {
                f.error("target_seconds", "a vertical video must stay at or under " + limits.verticalMaxSeconds()
                        + " s");
            }
            if ("landscape".equals(frame) && target.min() < limits.landscapeMinSeconds()) {
                f.error("target_seconds", "a landscape episode must be at least " + limits.landscapeMinSeconds()
                        + " s (mid-roll ads need 8 minutes)");
            }
        }
        VideoProfile p = new VideoProfile(
                f.text("id", true),
                f.positiveInt("version"),
                f.oneOf("genre", GENRES, null),
                f.text("tone", true),
                f.oneOf("facts", FACTS, null),
                f.text("structure", true),
                f.text("visual_style", true),
                f.oneOf("voices", VOICES, null),
                f.text("narrator_voice", false),
                frame,
                target,
                beat,
                f.oneOf("motion", MOTIONS, null),
                f.text("on_screen_text", true),
                f.oneOf("illustrations", ILLUSTRATIONS, "flux"),
                f.oneOf("opening", OPENINGS, "none"),
                f.bool("music", true),
                f.textList("music_mood"),
                f.oneOf("audience", AUDIENCES, "not_made_for_kids"),
                f.text("look_notes", false));
        if (!f.errors.isEmpty()) {
            throw new ProfileException(f.errors);
        }
        return p;
    }

    /** The profile as snake_case fields, the shape of the YAML file and of the story snapshot. */
    public Map<String, Object> fields() {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("version", version);
        m.put("genre", genre);
        m.put("tone", tone);
        m.put("facts", facts);
        m.put("structure", structure);
        m.put("visual_style", visualStyle);
        m.put("voices", voices);
        m.put("narrator_voice", narratorVoice);
        m.put("frame", frame);
        m.put("target_seconds", rangeFields(targetSeconds, true));
        m.put("beat_seconds", rangeFields(beatSeconds, false));
        m.put("motion", motion);
        m.put("on_screen_text", onScreenText);
        m.put("illustrations", illustrations);
        m.put("opening", opening);
        m.put("music", music);
        m.put("music_mood", musicMood);
        m.put("audience", audience);
        m.put("look_notes", lookNotes);
        return m;
    }

    private static Map<String, Object> rangeFields(Range r, boolean withTarget) {
        var m = new LinkedHashMap<String, Object>();
        if (withTarget) {
            m.put("target", r.target());
        }
        m.put("min", r.min());
        m.put("max", r.max());
        return m;
    }

    /** Reads typed fields from a YAML map, collecting one message per bad field. */
    private static final class Fields {

        final Map<String, Object> raw;
        final Map<String, String> errors = new LinkedHashMap<>();

        Fields(Map<String, Object> raw) {
            this.raw = raw == null ? Map.of() : raw;
        }

        void error(String key, String message) {
            errors.putIfAbsent(key, message);
        }

        String text(String key, boolean required) {
            Object v = raw.get(key);
            if (v == null || String.valueOf(v).isBlank()) {
                if (required) {
                    error(key, "missing");
                }
                return required ? null : "";
            }
            if (v instanceof Map || v instanceof List) {
                error(key, "must be text");
                return null;
            }
            return String.valueOf(v).strip();
        }

        String oneOf(String key, Set<String> allowed, String fallback) {
            Object v = raw.get(key);
            if (v == null || String.valueOf(v).isBlank()) {
                if (fallback == null) {
                    error(key, "missing; one of " + sorted(allowed));
                }
                return fallback;
            }
            String s = String.valueOf(v).strip();
            if (!allowed.contains(s)) {
                error(key, "\"" + s + "\" is not one of " + sorted(allowed));
                return fallback;
            }
            return s;
        }

        int positiveInt(String key) {
            Object v = raw.get(key);
            if (v instanceof Number n && n.intValue() >= 1 && n.doubleValue() == n.intValue()) {
                return n.intValue();
            }
            error(key, "must be a whole number from 1");
            return 0;
        }

        boolean bool(String key, boolean fallback) {
            Object v = raw.get(key);
            if (v == null) {
                return fallback;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            error(key, "must be true or false");
            return fallback;
        }

        List<String> textList(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return List.of();
            }
            if (!(v instanceof List<?> list)) {
                error(key, "must be a list, e.g. [tense, warm]");
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).strip());
                }
            }
            return List.copyOf(out);
        }

        /** {@code {target, min, max}} or {@code {min, max}}; target defaults to the middle. */
        Range range(String key, boolean withTarget) {
            Object v = raw.get(key);
            if (!(v instanceof Map<?, ?> m)) {
                error(key, withTarget ? "must be {target: 45, min: 30, max: 58}" : "must be {min: 2, max: 4}");
                return null;
            }
            Double min = number(m.get("min"));
            Double max = number(m.get("max"));
            Double target = m.containsKey("target") ? number(m.get("target")) : null;
            if (min == null || max == null || (m.containsKey("target") && target == null)) {
                error(key, "min, max" + (withTarget ? " and target" : "") + " must be numbers");
                return null;
            }
            double t = target == null ? (min + max) / 2 : target;
            if (min <= 0 || min > max || t < min || t > max) {
                error(key, "needs 0 < min <= " + (withTarget ? "target <= " : "") + "max");
                return null;
            }
            return new Range(t, min, max);
        }

        private static Double number(Object o) {
            return o instanceof Number n ? n.doubleValue() : null;
        }

        private static String sorted(Set<String> s) {
            return s.stream().sorted().toList().toString();
        }
    }
}

package com.storyforge.profile;

import com.storyforge.topic.Topic;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Topic profile settings: reading and checking a topic's overrides, and the snapshot a story takes at Run
 * (Pipeline D "Video profiles": profile, overrides and look notes, with the profile file's version), so later edits
 * never change a story in progress.
 */
@Component
public class ProfileSnapshots {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final VideoProfiles profiles;

    ProfileSnapshots(VideoProfiles profiles) {
        this.profiles = profiles;
    }

    /** Parses overrides typed as YAML ({@code narrator_voice: am_adam}); only overridable keys. */
    public static Map<String, Object> parseOverrides(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        Object parsed;
        try {
            parsed = YAML.readValue(text, Object.class);
        } catch (RuntimeException e) {
            throw new ProfileException(Map.of("overrides", "not valid YAML, e.g. \"narrator_voice: am_adam\""));
        }
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new ProfileException(Map.of("overrides", "write one \"field: value\" per line"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!VideoProfile.OVERRIDABLE.contains(key)) {
                throw new ProfileException(Map.of("overrides", "\"" + key + "\" is not a profile field that can be "
                        + "overridden; use one of " + VideoProfile.OVERRIDABLE.stream().sorted().toList()));
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    public static String toJson(Map<String, Object> map) {
        return map == null || map.isEmpty() ? null : JSON.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, Map.class);
    }

    /** Overrides as YAML text for the topic form. */
    public static String overridesAsYaml(String json) {
        Map<String, Object> m = fromJson(json);
        return m.isEmpty() ? "" : YAML.writeValueAsString(m).replaceFirst("^---\\s*\\n", "");
    }

    /** The merged profile a topic would run with today; throws if the file is missing or the result is invalid. */
    public VideoProfile effective(String profileId, Map<String, Object> overrides) {
        VideoProfiles.Loaded base = profiles.find(profileId)
                .orElseThrow(() -> new ProfileException(Map.of("profile", "no profile file named " + profileId)));
        return profiles.merge(base, overrides);
    }

    /** The snapshot JSON for a story of this topic, or empty for a topic without a profile (a fable). */
    public Optional<String> snapshot(Topic topic, String storyLookNotes) {
        if (topic.getProfileId() == null) {
            return Optional.empty();
        }
        Map<String, Object> overrides = fromJson(topic.getProfileOverrides());
        VideoProfiles.Loaded base;
        VideoProfile merged;
        try {
            base = profiles.find(topic.getProfileId()).orElseThrow(() -> new ProfileException(
                    "no profile file named " + topic.getProfileId()));
            merged = profiles.merge(base, overrides);
        } catch (ProfileException e) {
            throw new ProfileException("Topic \"" + topic.getName() + "\" can't run with its video profile \""
                    + topic.getProfileId() + "\": " + e.getMessage() + ". Fix the profile file or the topic's "
                    + "overrides, then Run again.");
        }
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("profile", blankToNull(merged.lookNotes()));
        notes.put("topic", blankToNull(topic.getLookNotes()));
        notes.put("story", blankToNull(storyLookNotes));
        notes.put("combined", combine(merged.lookNotes(), topic.getLookNotes(), storyLookNotes));

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("profile_id", base.profile().id());
        snap.put("profile_version", base.profile().version());
        snap.put("profile_file_sha256", base.fileHash());
        snap.put("overrides", overrides);
        snap.put("profile", merged.fields());
        snap.put("look_notes", notes);
        return Optional.of(JSON.writeValueAsString(snap));
    }

    /** Profile, topic and story notes in that order, each on its own paragraph; the story adds to the topic. */
    static String combine(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append(p.strip());
            }
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

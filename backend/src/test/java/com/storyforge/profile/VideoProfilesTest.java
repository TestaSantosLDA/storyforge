package com.storyforge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profile files and their validation (docs/pipeline-d/README.md "Video profiles", "Frames and lengths"). */
class VideoProfilesTest {

    static final VideoProfile.Limits LIMITS = new VideoProfile.Limits(58, 480);
    static final LookNotesCheck NAMES = new LookNotesCheck(List.of("Pixar", "Studio Ghibli"));

    @TempDir
    Path dir;

    VideoProfiles repoProfiles() {
        return new VideoProfiles(Path.of("../channel/video-profiles"), LIMITS, NAMES);
    }

    VideoProfiles tempProfiles() {
        return new VideoProfiles(dir, LIMITS, NAMES);
    }

    void write(String id, String yaml) throws Exception {
        Files.writeString(dir.resolve(id + ".yaml"), yaml);
    }

    static String profile(String id, String frame, String target, String extra) {
        return """
                id: %s
                version: 1
                genre: historic
                tone: grave
                facts: checked
                structure: hook, beats, payoff
                visual_style: ink and wash
                voices: narrator_only
                frame: %s
                target_seconds: %s
                beat_seconds: {min: 2, max: 4}
                motion: documentary
                on_screen_text: dates and places
                %s
                """.formatted(id, frame, target, extra);
    }

    @Test
    void the_four_starter_profiles_in_the_repo_are_valid() {
        assertThat(repoProfiles().all()).extracting(VideoProfile::id)
                .containsExactly("chronicle", "epic", "explainer", "tale");
    }

    @Test
    void epic_is_a_landscape_long_form_profile_drawn_entirely_in_code() {
        VideoProfile epic = repoProfiles().find("epic").orElseThrow().profile();
        assertThat(epic.landscape()).isTrue();
        assertThat(epic.targetSeconds()).isEqualTo(new VideoProfile.Range(540, 480, 660));
        assertThat(epic.beatSeconds()).isEqualTo(new VideoProfile.Range(4, 3, 5));
        assertThat(epic.genre()).isEqualTo("historic");
        assertThat(epic.facts()).isEqualTo("checked");
        assertThat(epic.motion()).isEqualTo("snappy");
        assertThat(epic.illustrations()).isEqualTo("none");
        assertThat(epic.opening()).isEqualTo("title_sting");
        assertThat(epic.music()).isTrue();
        assertThat(epic.visualStyle()).contains("#0a0f24", "#1f5fbf", "#ffc83d", "#ff4d3d", "#3de1ff", "#f6efe2");
        assertThat(epic.structure()).contains("Verse Moment", "Fact or Legend?", "teaser");
    }

    @Test
    void defaults_apply_for_optional_fields() throws Exception {
        write("plain", profile("plain", "vertical", "{target: 45, min: 30, max: 58}", ""));
        VideoProfile p = tempProfiles().find("plain").orElseThrow().profile();
        assertThat(p.illustrations()).isEqualTo("flux");
        assertThat(p.opening()).isEqualTo("none");
        assertThat(p.music()).isTrue();
        assertThat(p.audience()).isEqualTo("not_made_for_kids");
        assertThat(p.narratorVoice()).isEmpty();
    }

    @Test
    void a_vertical_profile_over_58_seconds_is_rejected() throws Exception {
        write("long", profile("long", "vertical", "{target: 80, min: 60, max: 90}", ""));
        assertThatThrownBy(() -> tempProfiles().find("long"))
                .isInstanceOf(ProfileException.class).hasMessageContaining("58 s");
        assertThat(tempProfiles().all()).isEmpty();
    }

    @Test
    void a_landscape_profile_under_8_minutes_is_rejected() throws Exception {
        write("short", profile("short", "landscape", "{target: 300, min: 240, max: 360}", ""));
        assertThatThrownBy(() -> tempProfiles().find("short"))
                .isInstanceOf(ProfileException.class).hasMessageContaining("480 s");
    }

    @Test
    void every_bad_field_is_reported_at_once() {
        assertThatThrownBy(() -> VideoProfile.parse(Map.of("id", "x", "genre", "comedy", "motion", "wobbly"), LIMITS))
                .isInstanceOfSatisfying(ProfileException.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("version", "genre", "motion", "tone", "facts", "target_seconds"));
    }

    @Test
    void the_id_must_match_the_file_name() throws Exception {
        write("one", profile("two", "vertical", "{target: 45, min: 30, max: 58}", ""));
        assertThatThrownBy(() -> tempProfiles().find("one")).hasMessageContaining("does not match");
    }

    @Test
    void a_profile_naming_a_studio_is_rejected() throws Exception {
        write("copy", profile("copy", "vertical", "{target: 45, min: 30, max: 58}",
                "look_notes: make it look like Pixar"));
        assertThatThrownBy(() -> tempProfiles().find("copy")).hasMessageContaining("Pixar");
    }

    @Test
    void overrides_merge_onto_the_file_and_are_validated() {
        VideoProfiles profiles = repoProfiles();
        VideoProfiles.Loaded chronicle = profiles.find("chronicle").orElseThrow();
        VideoProfile merged = profiles.merge(chronicle, Map.of("narrator_voice", "am_adam", "music", false));
        assertThat(merged.narratorVoice()).isEqualTo("am_adam");
        assertThat(merged.music()).isFalse();
        assertThat(merged.version()).isEqualTo(chronicle.profile().version());

        assertThatThrownBy(() -> profiles.merge(chronicle, Map.of("frame", "landscape")))
                .hasMessageContaining("480 s");
    }

    @Test
    void only_profile_fields_can_be_overridden() {
        assertThat(ProfileSnapshots.parseOverrides("narrator_voice: am_adam\nmusic: false"))
                .containsEntry("narrator_voice", "am_adam").containsEntry("music", false);
        assertThatThrownBy(() -> ProfileSnapshots.parseOverrides("version: 9"))
                .hasMessageContaining("\"version\" is not a profile field");
        assertThatThrownBy(() -> ProfileSnapshots.parseOverrides("just words"))
                .hasMessageContaining("field: value");
    }

    @Test
    void the_name_check_matches_whole_words_only() {
        assertThat(NAMES.findBanned("in the style of pixar")).contains("Pixar");
        assertThat(NAMES.findBanned("pixars and pixels")).isEmpty();
        assertThat(NAMES.findBanned("a Studio Ghibli feel")).contains("Studio Ghibli");
        assertThat(NAMES.findBanned(null)).isEmpty();
    }
}

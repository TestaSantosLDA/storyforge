package com.storyforge.stage4;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.storyforge.DbTest;
import com.storyforge.image.FakeImageEngine;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.notes.StoryNotes;
import com.storyforge.stage1.Gates;
import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.Stage1Fixtures;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioStep;
import com.storyforge.stage2.Pronunciation;
import com.storyforge.stage3.ShotStore;
import com.storyforge.stage3.VisualsStep;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import com.storyforge.voice.FakeSpeech;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/**
 * The Stage 4 scenarios table with real FFmpeg (fake Claude, Flux and Kokoro). Skipped when FFmpeg isn't available;
 * set STORYFORGE_FFMPEG_BIN to its folder if it isn't on PATH.
 */
@TestPropertySource(properties = {"storyforge.images.engine=fake", "storyforge.animation.engine=storybook"})
class Stage4ScenariosTest extends DbTest {

    static final String BY = "user:tester";
    static final String PIP = "pip-the-hedgehog";

    @DynamicPropertySource
    static void ffmpeg(DynamicPropertyRegistry r) {
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        if (bin != null) {
            r.add("storyforge.media.ffmpeg", () -> bin + "/ffmpeg");
            r.add("storyforge.media.ffprobe", () -> bin + "/ffprobe");
        }
    }

    @Autowired FakeLlmEngine claude;
    @Autowired FakeImageEngine images;
    @Autowired FakeSpeech speech;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryRepository stories;
    @Autowired StoryStatusService status;
    @Autowired StoryStep storyStep;
    @Autowired ScriptStep scriptStep;
    @Autowired Gates gates;
    @Autowired AudioStep audioStep;
    @Autowired VisualsStep visuals;
    @Autowired AssemblyStep assembly;
    @Autowired FinalReview review;
    @Autowired MusicLibrary music;
    @Autowired ShotStore shots;
    @Autowired StoryNotes notes;
    @Autowired Pronunciation pronunciation;
    @Autowired AssetStorage assets;

    long id;

    static boolean ffmpegAvailable() {
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        try {
            return new ProcessBuilder(bin == null ? "ffmpeg" : bin + "/ffmpeg", "-version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void storyAtAssembly() throws Exception {
        assumeTrue(ffmpegAvailable(), "FFmpeg not available");
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        Files.createDirectories(Path.of("target/test-characters"));
        Files.deleteIfExists(Path.of("target/test-pronunciation.yaml"));
        claude.reset();
        images.reset();
        speech.reset();
        claude.byDefault("story", Stage1Fixtures.story(PIP, "Pip", "am_puck"))
                .byDefault("story-qa", Stage1Fixtures.qaPass("structure"))
                .byDefault("sheet-qa", Stage1Fixtures.qaPass("anatomy"))
                .byDefault("script", Stage1Fixtures.script(PIP, 40))
                .byDefault("script-qa", Stage1Fixtures.qaPass("story_fidelity"))
                .byDefault("shots", com.storyforge.stage3.Stage3Fixtures.plan())
                .byDefault("shot-qa", Stage1Fixtures.qaPass("characters"));
        var t = topics.create("Whisperwood", "A misty wood.", BY);
        id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), BY).getId();
        queue.run(BY);
        storyStep.run(id);
        gates.approveStory(id, BY);
        scriptStep.run(id);
        gates.approveScript(id, BY);
        audioStep.run(id);
        visuals.run(id);
        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    void addTrack(String title, String mood, String license) throws Exception {
        Path tone = Files.createTempFile("tone-", ".wav");
        new ProcessBuilder(ffmpegBin("ffmpeg"), "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                "sine=frequency=330:duration=20", tone.toString()).start().waitFor();
        music.importTrack(title, Files.readAllBytes(tone), "tone.wav", 20, List.of(mood), "https://example.com/" + title,
                license, "License text".getBytes(), "license.txt", null);
    }

    static String ffmpegBin(String tool) {
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        return bin == null ? tool : bin + "/" + tool;
    }

    @Test
    void s1_assembled_with_music_subtitles_and_every_check_then_approved() throws Exception {
        addTrack("Quiet Woods", "calm", "pixabay");
        addTrack("Bright Morning", "happy_ending", "mixkit");

        assembly.run(id);

        assertThat(story().getStatus()).as(() -> story().getAttentionReason()).isEqualTo(AWAITING_FINAL_REVIEW);
        var row = jdbc.sql("select video_key, srt_key, qa::text, music::text, passed from assembly where story_id = :s")
                .param("s", id).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).single();
        assertThat(assets.exists(row.get(0))).isTrue();
        assertThat(assets.exists(row.get(1))).isTrue();
        assertThat(row.get(2)).contains("\"loudness\"", "\"sync\"", "\"subtitles\"").doesNotContain("\"pass\": false");
        assertThat(row.get(3)).contains("Quiet Woods", "Bright Morning");
        assertThat(Files.readString(assets.localPath(row.get(1)).orElseThrow())).contains("00:00:00,000 --> ", "w1 two");

        review.approve(id, BY);
        assertThat(story().getStatus()).isEqualTo(PREPARING_PUBLISH);
    }

    @Test
    void s7_rejecting_without_a_reason_is_refused() {
        assembly.run(id);
        assertThatThrownBy(() -> review.reject(id, "  ", BY)).isInstanceOf(InvalidInputException.class);
        assertThat(story().getStatus()).isEqualTo(AWAITING_FINAL_REVIEW);
    }

    @Test
    void s2_s3_s6_one_rejection_with_several_issues_redoes_only_the_named_shot_and_line() {
        assembly.run(id);
        claude.then("rejection", """
                {"confident":true,"summary":"Redraw SH03, re-record L005","targets":[
                 {"type":"shot","ids":["SH03"],"instruction":"Pip's scarf is mustard yellow, as in his reference.","word":"","say_as":""},
                 {"type":"audio_line","ids":["L005"],"instruction":"Pip is misread.","word":"w5","say_as":"double-u five"}]}
                """);

        review.reject(id, "Pip looks wrong at 0:12 and his name is misread at 0:24", BY);
        review.analyze(id);

        assertThat(story().getStatus()).as("earliest stage named: audio").isEqualTo(AUDIO_IN_PROGRESS);
        assertThat(story().getFinalRejections()).isEqualTo(1);
        assertThat(story().getScriptRejections()).as("video rejections never touch script counters").isZero();
        assertThat(notes.get(id, "shot:SH03")).contains("Pip's scarf is mustard yellow, as in his reference.");
        assertThat(shots.usable(id, "SH03")).as("SH03 will be drawn again").isEmpty();
        assertThat(shots.usable(id, "SH04")).isPresent();
        assertThat(pronunciation.entries()).containsEntry("w5", "double-u five");

        speech.mishear = c -> c.text().replace("double-u five", "w5");
        int drawsBefore = images.requests.size();
        audioStep.run(id);
        visuals.run(id);
        assertThat(images.requests.size() - drawsBefore).as("only SH03 redrawn").isEqualTo(1);
        assertThat(images.requests.getLast().prompt()).contains("Pip's scarf is mustard yellow");
        assembly.run(id);
        assertThat(story().getStatus()).isEqualTo(AWAITING_FINAL_REVIEW);
    }

    @Test
    void s4_music_too_loud_lowers_the_mix_and_reassembles() {
        assembly.run(id);
        claude.then("rejection", """
                {"confident":true,"summary":"Lower the music","targets":[
                 {"type":"mix","ids":[],"instruction":"quieter","word":"","say_as":""}]}
                """);

        review.reject(id, "music too loud", BY);
        review.analyze(id);

        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        assertThat(notes.get(id, "mix")).contains("-24.0");
    }

    @Test
    void s5_ending_does_not_land_goes_back_to_gate_b_with_notes() {
        assembly.run(id);
        claude.then("rejection", """
                {"confident":true,"summary":"Rewrite the ending","targets":[
                 {"type":"script","ids":[],"instruction":"Make the last scene show Pip choosing to share.","word":"","say_as":""}]}
                """);

        review.reject(id, "the ending doesn't land", BY);
        review.analyze(id);

        assertThat(story().getStatus()).isEqualTo(SCRIPT_IN_PROGRESS);
        scriptStep.run(id);
        assertThat(claude.requests("script").getLast().userPrompt()).contains("the ending doesn't land");
    }

    @Test
    void s8_a_vague_rejection_is_flagged_for_a_person() {
        assembly.run(id);
        claude.then("rejection", "{\"confident\":false,\"summary\":\"unclear\",\"targets\":[]}");

        review.reject(id, "not great", BY);
        review.analyze(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("couldn't be mapped");
    }

    @Test
    void s9_third_rejection_stops_for_a_person_instead_of_archiving() {
        for (int i = 1; i <= 2; i++) {
            assembly.run(id);
            claude.then("rejection", "{\"confident\":true,\"summary\":\"mix\",\"targets\":[{\"type\":\"mix\",\"ids\":[],"
                    + "\"instruction\":\"quieter\",\"word\":\"\",\"say_as\":\"\"}]}");
            review.reject(id, "music too loud " + i, BY);
            review.analyze(id);
        }
        assembly.run(id);

        review.reject(id, "still too loud", BY);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Third final rejection");
        assertThat(story().getStatus()).isNotEqualTo(ARCHIVED);
    }

    @Test
    void s10_a_track_without_a_valid_license_record_is_never_used() throws Exception {
        addTrack("Good Track", "calm", "pixabay");
        var track = music.all().getFirst();
        Files.delete(assets.localPath(track.licenseKey()).orElseThrow());

        assembly.run(id);

        assertThat(story().getStatus()).isEqualTo(AWAITING_FINAL_REVIEW);
        assertThat(jdbc.sql("select music::text from assembly where story_id = :s").param("s", id)
                .query(String.class).single()).doesNotContain("Good Track");
    }

    @Test
    void music_with_a_non_commercial_license_is_rejected_at_import() {
        assertThatThrownBy(() -> music.importTrack("Bad", new byte[] {1}, "a.mp3", 10, List.of("calm"),
                "https://example.com", "cc_by_nc", new byte[] {1}, "l.txt", null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors().get("license")).contains("Non-commercial"));
        assertThat(new ArrayList<>(music.all())).isEmpty();
    }
}

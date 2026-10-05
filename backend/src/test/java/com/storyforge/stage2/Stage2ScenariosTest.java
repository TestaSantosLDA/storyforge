package com.storyforge.stage2;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.Gates;
import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.Stage1Fixtures;
import com.storyforge.stage1.StoryStep;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.TopicService;
import com.storyforge.voice.FakeSpeech;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Stage 2 scenarios table (docs/pipeline-a/stage-2), with fake Kokoro and Whisper. */
class Stage2ScenariosTest extends DbTest {

    static final String BY = "user:tester";

    @Autowired FakeLlmEngine claude;
    @Autowired FakeSpeech speech;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryRepository stories;
    @Autowired StoryStatusService status;
    @Autowired StoryStep storyStep;
    @Autowired ScriptStep scriptStep;
    @Autowired Gates gates;
    @Autowired Drafts drafts;
    @Autowired AudioStep audioStep;
    @Autowired AudioLines audio;
    @Autowired Pronunciation pronunciation;
    @Autowired AssetStorage assets;
    @Autowired CharacterStore characters;

    long id;

    @BeforeEach
    void storyAtAudio() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        Files.deleteIfExists(Path.of("target/test-pronunciation.yaml"));
        Files.createDirectories(Path.of("target/test-characters"));
        claude.reset();
        speech.reset();
        claude.byDefault("story", Stage1Fixtures.story("pip-the-hedgehog", "Pip", "am_puck"))
                .byDefault("story-qa", Stage1Fixtures.qaPass("structure"))
                .byDefault("script", Stage1Fixtures.script("pip-the-hedgehog", 40))
                .byDefault("script-qa", Stage1Fixtures.qaPass("story_fidelity"));
        var t = topics.create("Whisperwood", "A misty wood.", BY);
        id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), BY).getId();
        queue.run(BY);
        storyStep.run(id);
        gates.approveStory(id, BY);
        scriptStep.run(id);
        gates.approveScript(id, BY);
        assertThat(story().getStatus()).isEqualTo(AUDIO_IN_PROGRESS);
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    JsonNode manifest() {
        return JsonMapper.builder().build().readTree(audio.track(id).orElseThrow().manifest());
    }

    @Test
    void s1_happy_path_every_line_voiced_checked_stitched_and_mapped() {
        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
        assertThat(speech.calls).hasSize(40);
        assertThat(speech.calls).filteredOn(c -> c.voice().equals("am_puck")).hasSize(8); // every 5th line is Pip
        assertThat(speech.calls).filteredOn(c -> c.voice().equals("bm_george")).hasSize(32); // narrator
        JsonNode lines = manifest().get("lines");
        assertThat(lines).hasSize(40);
        // L001 and L002 are both the narrator in scene S01: 0.3 s pause. L004 → L005 changes speaker: 0.6 s.
        double gapSame = lines.get(1).get("start").asDouble() - lines.get(0).get("end").asDouble();
        double gapChange = lines.get(4).get("start").asDouble() - lines.get(3).get("end").asDouble();
        double gapScene = lines.get(20).get("start").asDouble() - lines.get(19).get("end").asDouble();
        assertThat(gapSame).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.002));
        assertThat(gapChange).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.002));
        assertThat(gapScene).isCloseTo(1.2, org.assertj.core.data.Offset.offset(0.002));
        assertThat(lines.get(20).get("scene").asString()).isEqualTo("S02");
        assertThat(assets.exists("stories/" + id + "/audio/narration.wav")).isTrue();
        double total = manifest().get("duration").asDouble();
        assertThat(audio.track(id).orElseThrow().durationSec()).isCloseTo(total, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void s2_a_misheard_line_is_redone_at_a_different_speed_and_the_rest_untouched() {
        speech.mishear = c -> c.text().startsWith("w1 ") && c.speed() == 1.0 ? "completely different words here" : c.text();

        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
        assertThat(speech.calls).hasSize(41);
        assertThat(speech.calls.get(1).speed()).isEqualTo(0.95);
        assertThat(audio.forStory(id)).filteredOn(l -> !l.passed()).singleElement()
                .satisfies(l -> assertThat(l.lineId()).isEqualTo("L001"));
    }

    @Test
    void s3_s4_line_failing_three_times_flags_then_a_pronunciation_entry_fixes_only_that_line() {
        speech.mishear = c -> c.text().startsWith("w5 ") ? "something else entirely" : c.text();

        audioStep.run(id);

        Story flagged = story();
        assertThat(flagged.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(flagged.getAttentionReason()).contains("L005", "failed 3 times", "transcript_match");
        int callsBefore = speech.calls.size();

        // Whisper writes the word as it is normally spelled, whatever respelling the engine was given.
        speech.mishear = c -> c.text().replace("double-u five", "w5");
        pronunciation.put("w5", "double-u five");
        status.resume(id, BY, "pronunciation added");
        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
        List<FakeSpeech.Call> after = speech.calls.subList(callsBefore, speech.calls.size());
        assertThat(after).as("only the failing line is redone").singleElement()
                .satisfies(c -> assertThat(c.text()).startsWith("double-u five"));
        assertThat(drafts.approved(id, Drafts.Kind.SCRIPT).orElseThrow().content())
                .as("the approved script never changes").contains("w5 ").doesNotContain("double-u");
    }

    @Test
    void s5_narration_outside_the_length_tolerance_flags_and_can_be_accepted() {
        speech.wordsPerSecond = 4.4; // 520 words in under 2.5 minutes

        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Narration is", "outside 2.5–6.0 min");

        audio.acceptDuration(id);
        status.resume(id, BY, "accept");
        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
        assertThat(speech.calls).as("passing clips reused, nothing regenerated").hasSize(40);
    }

    @Test
    void s6_send_back_to_gate_b_rewrites_the_script_with_notes() {
        speech.wordsPerSecond = 4.4;
        audioStep.run(id);

        assertThat(gates.sendBackToScript(id, "shorten the middle", BY)).isEqualTo(Gates.Outcome.REGENERATING);

        assertThat(story().getStatus()).isEqualTo(SCRIPT_IN_PROGRESS);
        assertThat(story().getScriptRejections()).isEqualTo(1);
        scriptStep.run(id);
        assertThat(claude.requests("script").getLast().userPrompt()).contains("shorten the middle");
    }

    @Test
    void s7_a_speaker_without_a_voice_is_flagged_before_anything_is_generated() {
        CharacterFile pip = characters.get("pip-the-hedgehog").orElseThrow();
        characters.update(new CharacterFile(pip.id(), pip.name(), pip.topicId(), pip.kind(), pip.personality(),
                pip.visualDescription(), pip.referenceImages(), Map.of(), pip.introducedInStory()));

        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("pip-the-hedgehog", "no voice");
        assertThat(speech.calls).isEmpty();
    }

    @Test
    void s8_sidecar_down_backs_off_then_flags_without_counting_attempts() {
        speech.failNext = 100;

        audioStep.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Voice sidecar unavailable");
        assertThat(audio.forStory(id)).isEmpty();
    }

    @Test
    void s9_rerun_after_a_restart_reuses_every_passing_clip() {
        speech.failNext = 0;
        audioStep.run(id);
        int calls = speech.calls.size();
        jdbc.sql("update story set status = 'audio_in_progress' where id = :id").param("id", id).update();

        audioStep.run(id);

        assertThat(speech.calls).hasSize(calls);
        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
    }

    @Test
    void short_lines_are_judged_by_length_not_by_transcript_or_words_per_second() {
        var clip = new com.storyforge.voice.SpeechClip(new byte[0], "x", 1.1, 0.5, 0.0, 0);
        // Real run: "Oh." was heard as "Though?" (0% match) and "Very atmospheric." ran 2 s (1 word/s).
        assertThat(audioStep.checks("Oh.", clip, 0.0, "af_sky")).allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
        var two = new com.storyforge.voice.SpeechClip(new byte[0], "x", 2.0, 0.5, 0.0, 0);
        assertThat(audioStep.checks("Very atmospheric.", two, 1.0, "bm_fable"))
                .allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
        var stretched = new com.storyforge.voice.SpeechClip(new byte[0], "x", 6.0, 0.5, 0.0, 0);
        assertThat(audioStep.checks("Oh.", stretched, 0.0, "af_sky")).anyMatch(c -> !Boolean.TRUE.equals(c.get("pass")));
        var longMisheard = new com.storyforge.voice.SpeechClip(new byte[0], "x", 4.0, 0.5, 0.0, 0);
        assertThat(audioStep.checks("the mist lay thick as porridge over the wood", longMisheard, 0.5, "bm_george"))
                .anyMatch(c -> !Boolean.TRUE.equals(c.get("pass")));
    }
}

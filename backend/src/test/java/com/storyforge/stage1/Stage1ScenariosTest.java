package com.storyforge.stage1;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.DbTest;
import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileSystemUtils;

/** The Stage 1 scenarios table (docs/pipeline-a/stage-1 "Scenarios"), with a scripted fake Claude. */
class Stage1ScenariosTest extends DbTest {

    static final String BY = "user:tester";

    @Autowired FakeLlmEngine claude;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryRepository stories;
    @Autowired StoryStep storyStep;
    @Autowired ScriptStep scriptStep;
    @Autowired Gates gates;
    @Autowired Drafts drafts;
    @Autowired CharacterStore characters;
    @Autowired com.storyforge.story.AutoResumer autoResumer;

    Topic topic;
    long id;

    @BeforeEach
    void setUp() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        Files.createDirectories(Path.of("target/test-characters"));
        claude.reset();
        claude.byDefault("story", story("pip-the-hedgehog", "Pip", "am_puck"))
                .byDefault("story-qa", qaPass("structure", "moral", "cast_justification", "visual_distinctness",
                        "consistency", "tone_and_safety"))
                .byDefault("script", script("pip-the-hedgehog", 40))
                .byDefault("script-qa", qaPass("story_fidelity", "moral", "reading_level_tone_safety"));
        topic = topics.create("Whisperwood", "A misty wood.", BY);
        id = queue.add(new StoryForm(topic.getId(), "Bramble learns to listen", "Listening matters", 4, null), BY)
                .getId();
        queue.run(BY);
    }

    // ---------- fixtures ----------

    static String story(String newId, String newName, String voice) {
        return """
                {"title":"The Quiet Clearing","outline":{"setup":"s","problem":"p","journey":"j","resolution":"r",
                 "moral_moment":"m"},"beats":["one","two","three","four","five"],"moral":"Listening matters",
                 "reused_characters":[],
                 "new_characters":[{"id":"%s","name":"%s","kind":"recurring","personality":"shy",
                   "visual_description":"small round hedgehog with a mustard scarf","proposed_voice":"%s",
                   "reason":"needs a friend to listen to"}]}
                """.formatted(newId, newName, voice);
    }

    static String qaPass(String... names) {
        return "{\"checks\":[" + java.util.Arrays.stream(names)
                .map(n -> "{\"name\":\"" + n + "\",\"pass\":true,\"feedback\":\"\"}").collect(Collectors.joining(","))
                + "]}";
    }

    static String qaFail(String name, String feedback) {
        return "{\"checks\":[{\"name\":\"" + name + "\",\"pass\":false,\"feedback\":\"" + feedback + "\"}]}";
    }

    /** {@code lines} narrator/character lines of 13 words each (40 → 520 words, inside 416–624 for 4 min). */
    static String script(String characterId, int lines) {
        List<String> ls = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            String speaker = i % 5 == 0 ? characterId : "narrator";
            ls.add("{\"id\":\"L%03d\",\"speaker\":\"%s\",\"text\":\"%s\"}".formatted(i, speaker,
                    "one two three four five six seven eight nine ten eleven twelve thirteen"));
        }
        String ids1 = IntStream.rangeClosed(1, lines / 2).mapToObj(i -> "\"L%03d\"".formatted(i)).collect(Collectors.joining(","));
        String ids2 = IntStream.rangeClosed(lines / 2 + 1, lines).mapToObj(i -> "\"L%03d\"".formatted(i)).collect(Collectors.joining(","));
        return "{\"lines\":[" + String.join(",", ls) + "],\"scenes\":["
                + "{\"id\":\"S01\",\"visual_description\":\"clearing\",\"mood\":\"calm\",\"line_ids\":[" + ids1 + "]},"
                + "{\"id\":\"S02\",\"visual_description\":\"stream\",\"mood\":\"happy_ending\",\"line_ids\":[" + ids2 + "]}]}";
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    StoryStatus status() {
        return story().getStatus();
    }

    void toScriptGate() {
        storyStep.run(id);
        gates.approveStory(id, BY);
        scriptStep.run(id);
    }

    // ---------- scenarios ----------

    @Test
    void s1_happy_path_story_and_script_approved() {
        storyStep.run(id);
        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);

        gates.approveStory(id, BY);
        assertThat(status()).isEqualTo(SCRIPT_IN_PROGRESS);
        CharacterFile pip = characters.get("pip-the-hedgehog").orElseThrow();
        assertThat(pip.topicId()).isEqualTo(topic.getId());
        assertThat(pip.introducedInStory()).isEqualTo(id);
        assertThat(pip.voice()).containsEntry("id", "am_puck");

        scriptStep.run(id);
        assertThat(status()).isEqualTo(AWAITING_SCRIPT_APPROVAL);
        assertThat(claude.requests("script").getFirst().userPrompt()).contains("pip-the-hedgehog", "The Quiet Clearing");

        gates.approveScript(id, BY);
        assertThat(status()).isEqualTo(SCRIPT_APPROVED);
        assertThat(drafts.approved(id, Drafts.Kind.SCRIPT)).isPresent();
        assertThat(jdbc.sql("select count(*) from attempt where story_id = :id").param("id", id)
                .query(Long.class).single()).as("generate + QA for story and script").isEqualTo(4);
    }

    @Test
    void s2_story_qa_fails_with_budget_left_and_regenerates_with_the_failure_as_feedback() {
        claude.then("story-qa", qaFail("moral", "the moral never lands"));

        storyStep.run(id);

        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(story().getStoryQaRetries()).isEqualTo(1);
        assertThat(claude.requests("story")).hasSize(2);
        assertThat(claude.requests("story").get(1).userPrompt()).contains("moral: the moral never lands");
    }

    @Test
    void s3_new_character_too_similar_to_an_existing_one_fails_and_the_clash_is_named() {
        characters.create(new CharacterFile("pipp", "Pipp", 99L, CharacterFile.Kind.RECURRING, "p", "v",
                List.of(), java.util.Map.of("engine", "kokoro", "id", "af_sky"), 1L));
        claude.then("story", story("pip-the-hedgehog", "Pip", "am_puck"))
                .then("story", story("hazel-wren", "Hazel", "am_puck"));

        storyStep.run(id);

        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(story().getStoryQaRetries()).isEqualTo(1);
        assertThat(claude.requests("story").get(1).userPrompt()).contains("too close to the existing character \"Pipp\"");
    }

    @Test
    void s3b_voice_already_used_in_the_universe_fails() {
        characters.create(new CharacterFile("old-badger", "Old Badger", topic.getId(), CharacterFile.Kind.RECURRING,
                "p", "v", List.of(), java.util.Map.of("engine", "kokoro", "id", "am_puck"), 1L));
        claude.then("story", story("pip-the-hedgehog", "Pip", "am_puck"))
                .byDefault("story", story("pip-the-hedgehog", "Pip", "am_adam"));

        storyStep.run(id);

        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(story().getStoryQaRetries()).isEqualTo(1);
        assertThat(claude.requests("story").get(1).userPrompt()).contains("voice \"am_puck\" is not free");
        assertThat(claude.requests("story").getFirst().userPrompt()).doesNotContain("am_puck,");
    }

    @Test
    void s4_reviewer_regenerates_story_with_notes() {
        storyStep.run(id);

        assertThat(gates.regenerate(id, Gates.Gate.STORY, "make the hedgehog braver", BY))
                .isEqualTo(Gates.Outcome.REGENERATING);
        assertThat(status()).isEqualTo(STORY_IN_PROGRESS);
        assertThat(story().getStoryRejections()).isEqualTo(1);

        storyStep.run(id);
        assertThat(claude.requests("story").get(1).userPrompt()).contains("make the hedgehog braver");
        assertThat(drafts.all(id, Drafts.Kind.STORY)).hasSize(2);
        assertThatThrownBy(() -> gates.regenerate(id, Gates.Gate.STORY, " ", BY))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void s5_story_qa_budget_used_up_archives() {
        claude.byDefault("story-qa", qaFail("structure", "no ending"));

        storyStep.run(id);

        assertThat(status()).isEqualTo(ARCHIVED);
        assertThat(story().getArchiveReason()).isEqualTo(ArchiveReason.STORY_QA_RETRIES_EXHAUSTED);
        assertThat(story().getStoryQaRetries()).isEqualTo(5);
        assertThat(characters.all()).isEmpty();
    }

    @Test
    void s5b_third_story_rejection_archives() {
        for (int i = 1; i <= 2; i++) {
            storyStep.run(id);
            gates.regenerate(id, Gates.Gate.STORY, "again " + i, BY);
        }
        storyStep.run(id);
        assertThat(gates.regenerate(id, Gates.Gate.STORY, "third", BY)).isEqualTo(Gates.Outcome.ARCHIVED);
        assertThat(story().getArchiveReason()).isEqualTo(ArchiveReason.STORY_REJECTIONS_EXHAUSTED);
    }

    @Test
    void s6_kill_at_gate_a_archives_without_character_files() {
        storyStep.run(id);
        gates.kill(id, Gates.Gate.STORY, BY);
        assertThat(status()).isEqualTo(ARCHIVED);
        assertThat(characters.all()).isEmpty();
    }

    @Test
    void s7_script_with_an_unapproved_speaker_fails_cast_lock_and_regenerates() {
        claude.then("script", script("mystery-owl", 40));

        toScriptGate();

        assertThat(status()).isEqualTo(AWAITING_SCRIPT_APPROVAL);
        assertThat(story().getScriptQaRetries()).isEqualTo(1);
        assertThat(claude.requests("script").get(1).userPrompt()).contains("cast_lock", "mystery-owl");
    }

    @Test
    void s8_script_too_short_fails_length_then_reviewer_regenerates() {
        claude.then("script", script("pip-the-hedgehog", 10));
        toScriptGate();
        assertThat(story().getScriptQaRetries()).isEqualTo(1);
        assertThat(claude.requests("script").get(1).userPrompt()).contains("130 words; needs 416 to 624");

        gates.regenerate(id, Gates.Gate.SCRIPT, "more about the stream", BY);
        assertThat(story().getScriptRejections()).isEqualTo(1);
        scriptStep.run(id);
        assertThat(claude.requests("script").get(2).userPrompt()).contains("more about the stream");
    }

    @Test
    void s9_script_budget_used_up_archives_and_characters_stay() {
        claude.byDefault("script-qa", qaFail("story_fidelity", "beats out of order"));

        toScriptGate();

        assertThat(status()).isEqualTo(ARCHIVED);
        assertThat(story().getArchiveReason()).isEqualTo(ArchiveReason.SCRIPT_QA_RETRIES_EXHAUSTED);
        assertThat(characters.get("pip-the-hedgehog")).isPresent();
    }

    @Test
    void s10_kill_at_gate_b_archives_and_characters_stay() {
        toScriptGate();
        gates.kill(id, Gates.Gate.SCRIPT, BY);
        assertThat(status()).isEqualTo(ARCHIVED);
        assertThat(characters.get("pip-the-hedgehog")).isPresent();
    }

    @Test
    void s11_claude_unavailable_backs_off_then_flags_without_a_strike() {
        claude.byDefault("story", FakeLlmEngine.UNAVAILABLE);

        storyStep.run(id);

        assertThat(status()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getResumeStatus()).isEqualTo(STORY_IN_PROGRESS);
        assertThat(story().getStoryQaRetries()).as("outages are never strikes").isZero();
        assertThat(claude.requests("story")).as("1 call + 3 retries").hasSize(4);
    }

    @Test
    void s11c_usage_limit_flags_with_an_automatic_resume_and_resumes_when_due() {
        claude.then("story", FakeLlmEngine.USAGE_LIMIT);

        storyStep.run(id);

        Story flagged = story();
        assertThat(flagged.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(flagged.getAttentionReason()).contains("usage limit", "resumes automatically");
        assertThat(flagged.getAutoResumeAt()).isAfter(java.time.Instant.now().plusSeconds(3500));
        assertThat(claude.requests("story")).as("no pointless backoff retries on a usage limit").hasSize(1);

        autoResumer.resumeDue();
        assertThat(status()).as("not due yet").isEqualTo(NEEDS_ATTENTION);

        jdbc.sql("update story set auto_resume_at = now() - interval '1 minute' where id = :id").param("id", id).update();
        autoResumer.resumeDue();
        assertThat(status()).isEqualTo(STORY_IN_PROGRESS);
        assertThat(story().getAutoResumeAt()).isNull();
    }

    @Test
    void s11b_unparseable_answer_counts_as_a_format_failure() {
        claude.then("story", FakeLlmEngine.BAD_OUTPUT);
        storyStep.run(id);
        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(story().getStoryQaRetries()).isEqualTo(1);
        assertThat(claude.requests("story").get(1).userPrompt()).contains("format:");
    }

    @Test
    void s13_rerun_after_a_restart_does_not_double_count() {
        claude.then("story-qa", qaFail("moral", "weak"));
        storyStep.run(id); // one failure counted, then passes
        jdbc.sql("update story set status = 'story_in_progress' where id = :id").param("id", id).update();

        storyStep.run(id); // as if restarted mid-generation

        assertThat(story().getStoryQaRetries()).isEqualTo(1);
    }

    @Test
    void approving_a_cast_whose_name_was_taken_meanwhile_rolls_back() {
        storyStep.run(id);
        characters.create(new CharacterFile("pip-the-hedgehog", "Someone Else", 5L, CharacterFile.Kind.ONE_OFF, "p",
                "v", List.of(), null, 1L));

        assertThatThrownBy(() -> gates.approveStory(id, BY)).isInstanceOf(InvalidInputException.class);
        assertThat(status()).isEqualTo(AWAITING_STORY_APPROVAL);
    }
}

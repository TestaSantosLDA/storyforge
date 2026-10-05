package com.storyforge.stage3;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.storyforge.DbTest;
import com.storyforge.animation.FakeAnimator;
import com.storyforge.image.FakeImageEngine;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.Gates;
import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.Stage1Fixtures;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage2.AudioStep;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.TopicService;
import com.storyforge.voice.FakeSpeech;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/** The Stage 3 scenarios table (docs/pipeline-a/stage-3), with fake Claude, Flux and FFmpeg. */
@TestPropertySource(properties = "storyforge.images.engine=fake")
class Stage3ScenariosTest extends DbTest {

    static final String BY = "user:tester";
    static final String PIP = "pip-the-hedgehog";

    @Autowired FakeLlmEngine claude;
    @Autowired FakeImageEngine images;
    @Autowired FakeSpeech speech;
    @Autowired FakeAnimator animator;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryRepository stories;
    @Autowired StoryStatusService status;
    @Autowired StoryStep storyStep;
    @Autowired ScriptStep scriptStep;
    @Autowired Gates gates;
    @Autowired AudioStep audioStep;
    @Autowired AudioLines audio;
    @Autowired VisualsStep visuals;
    @Autowired ShotStore shots;
    @Autowired Drafts drafts;
    @Autowired ShotPlanner planner;

    long id;

    @BeforeEach
    void storyAtVisuals() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        Files.createDirectories(Path.of("target/test-characters"));
        Files.deleteIfExists(Path.of("target/test-pronunciation.yaml"));
        claude.reset();
        images.reset();
        speech.reset();
        animator.reset();
        claude.byDefault("story", Stage1Fixtures.story(PIP, "Pip", "am_puck"))
                .byDefault("story-qa", Stage1Fixtures.qaPass("structure"))
                .byDefault("sheet-qa", Stage1Fixtures.qaPass("anatomy"))
                .byDefault("script", Stage1Fixtures.script(PIP, 40))
                .byDefault("script-qa", Stage1Fixtures.qaPass("story_fidelity"))
                .byDefault("shots", plan(i -> i % 5 == 0 ? List.of(PIP) : List.of()))
                .byDefault("shot-qa", Stage1Fixtures.qaPass("characters", "consistency"));
        var t = topics.create("Whisperwood", "A misty wood.", BY);
        id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), BY).getId();
        queue.run(BY);
        storyStep.run(id);
        gates.approveStory(id, BY);
        scriptStep.run(id);
        gates.approveScript(id, BY);
        audioStep.run(id);
        assertThat(story().getStatus()).isEqualTo(VISUALS_IN_PROGRESS);
        images.reset(); // count only Stage 3 draws below
    }

    /** One shot per line (lines are ~5.5 s), scene S01 = L001–L020, S02 = L021–L040. */
    static String plan(IntFunction<List<String>> charactersForLine) {
        List<String> shots = new ArrayList<>();
        String[] cams = {"zoom_in", "pan_left", "zoom_out", "pan_right", "hold"};
        for (int i = 1; i <= 40; i++) {
            List<String> cs = charactersForLine.apply(i);
            String actions = cs.stream().map(c -> "{\"character\":\"" + c + "\",\"action\":\"Pip looks up at the lantern.\"}")
                    .reduce((a, b) -> a + "," + b).orElse("");
            shots.add(("{\"id\":\"SH%02d\",\"scene_id\":\"%s\",\"line_ids\":[\"L%03d\"],\"characters\":[%s],"
                    + "\"actions\":[%s],\"setting\":\"A misty clearing at dusk.\",\"framing\":\"medium\","
                    + "\"camera\":\"%s\"}").formatted(i, i <= 20 ? "S01" : "S02", i,
                    cs.stream().map(c -> "\"" + c + "\"").reduce((a, b) -> a + "," + b).orElse(""), actions,
                    cams[i % cams.length]));
        }
        return "{\"shots\":[" + String.join(",", shots) + "]}";
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    ShotPlan storedPlan() {
        return ShotPlanner.JSON.readValue(drafts.approved(id, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class);
    }

    @Test
    void s1_happy_path_shots_planned_drawn_checked_and_animated_to_the_exact_length() {
        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        ShotPlan plan = storedPlan();
        assertThat(plan.shots()).hasSize(40);
        double trackEnd = audio.track(id).orElseThrow().durationSec();
        for (int i = 0; i < plan.shots().size(); i++) {
            ShotPlan.Shot s = plan.shots().get(i);
            double next = i + 1 < plan.shots().size() ? plan.shots().get(i + 1).start() : trackEnd;
            assertThat(s.end()).as("shots cover the timeline with no gaps").isEqualTo(next);
        }
        assertThat(plan.shots().getFirst().start()).isZero();
        assertThat(images.requests).hasSize(40);
        assertThat(images.requests).allMatch(r -> r.width() == 1360 && r.height() == 768 && r.referenceMaxPx() == 512);
        assertThat(animator.calls).hasSize(40);
        // Clips are cut on the 25 fps frame grid: each within a frame of its shot, and no drift adds up.
        double total = 0;
        for (int i = 0; i < 40; i++) {
            assertThat(animator.calls.get(i).seconds()).isCloseTo(plan.shots().get(i).seconds(), within(0.04 + 1e-9));
            total += animator.calls.get(i).seconds();
        }
        assertThat(total).isCloseTo(Math.round(trackEnd * 25) / 25.0, within(1e-6));
        assertThat(shots.clips(id)).hasSize(40);
    }

    @Test
    void shot_prompts_carry_actions_descriptions_and_two_references_per_character() {
        visuals.run(id);

        var withPip = images.requests.get(4); // SH05 shows Pip
        assertThat(withPip.references()).hasSize(2);
        assertThat(withPip.prompt()).contains("Medium shot", "A misty clearing at dusk.", "Pip looks up at the lantern.",
                "Pip is the character in reference images 1 and 2", "small round hedgehog", "Only these characters appear");
        assertThat(images.requests.get(0).references()).isEmpty();
        assertThat(claude.requests("shot-qa").get(4).images()).as("shot + 2 references").hasSize(3);
    }

    @Test
    void s2_to_s5_a_failing_image_is_redrawn_with_a_new_seed_and_the_same_prompt() {
        claude.then("shot-qa", Stage1Fixtures.qaPass("characters")); // SH01 passes
        claude.then("shot-qa", "{\"checks\":[{\"name\":\"consistency\",\"pass\":false,\"feedback\":\"Pip's scarf is red\"}]}");

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        assertThat(images.requests).hasSize(41);
        assertThat(images.requests.get(2).prompt()).isEqualTo(images.requests.get(1).prompt()).doesNotContain("scarf is red");
        assertThat(images.requests.get(2).seed()).isNotEqualTo(images.requests.get(1).seed());
    }

    @Test
    void s6_a_shot_failing_three_times_flags_and_the_best_draw_can_be_accepted() {
        claude.then("shot-qa", Stage1Fixtures.qaPass("characters"));
        for (int i = 0; i < 3; i++) {
            claude.then("shot-qa", "{\"checks\":[{\"name\":\"artifacts\",\"pass\":false,\"feedback\":\"extra paw\"}]}");
        }

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("SH02", "failed QA 3 times");
        assertThat(shots.usable(id, "SH01")).as("passing shots are kept").isPresent();
        assertThat(animator.calls).as("nothing animated until every shot has an image").isEmpty();

        shots.accept(shots.best(id, "SH02").orElseThrow().id());
        status.resume(id, BY, "accepted best");
        int drawn = images.requests.size();
        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        assertThat(images.requests).as("only the missing shots are drawn").hasSize(drawn);
    }

    @Test
    void s7_a_plan_with_too_many_characters_or_strangers_is_redone_with_the_problems_named() {
        claude.then("shots", plan(i -> i == 3 ? List.of(PIP, "a", "b", "c", "d") : List.of()));

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        assertThat(claude.requests("shots")).hasSize(2);
        assertThat(claude.requests("shots").get(1).userPrompt()).contains("SH03 has 5 characters", "not in the cast");
    }

    @Test
    void a_plan_that_keeps_failing_flags_the_story() {
        claude.byDefault("shots", "{\"shots\":[{\"id\":\"SH01\",\"scene_id\":\"S01\",\"line_ids\":[\"L001\"],"
                + "\"characters\":[],\"actions\":[],\"setting\":\"x\",\"framing\":\"wide\",\"camera\":\"hold\"}]}");

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Shot plan failed", "L002 is in no shot");
        assertThat(images.requests).isEmpty();
    }

    @Test
    void s8_out_of_memory_or_sidecar_down_flags_without_counting_a_shot_attempt() {
        images.failNext = 1;

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Image sidecar unavailable");
        assertThat(shots.images(id)).isEmpty();
    }

    @Test
    void s9_rerun_after_a_restart_keeps_every_image_and_clip() {
        visuals.run(id);
        int drawn = images.requests.size();
        int animated = animator.calls.size();
        jdbc.sql("update story set status = 'visuals_in_progress' where id = :id").param("id", id).update();

        visuals.run(id);

        assertThat(images.requests).hasSize(drawn);
        assertThat(animator.calls).hasSize(animated);
        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
    }

    @Test
    void s10_vision_qa_unavailable_flags_after_backoff() {
        claude.byDefault("shot-qa", FakeLlmEngine.UNAVAILABLE);

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Claude unavailable");
    }

    @Test
    void a_clip_with_black_frames_fails_clip_qa_and_flags() {
        animator.blackFrames = true;

        visuals.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Animation failed", "black frames");
    }

    @Test
    void over_long_shots_are_split_and_short_ones_merged_without_asking_claude_again() {
        // Real run: Claude's 2-3 line shots ran 11-13 s once pauses were counted, and one ran 4.4 s.
        var shot = (java.util.function.BiFunction<String, List<String>, ShotPlan.Shot>) (scene, ids) ->
                new ShotPlan.Shot("X", scene, ids, List.of(), List.of(), "s", "wide", "hold", null, null);
        var onScreen = java.util.Map.of("L1", 6.0, "L2", 6.0, "L3", 2.0, "L4", 3.0, "L5", 6.0);
        ShotPlan repaired = planner.repair(new ShotPlan(List.of(
                shot.apply("S01", List.of("L1", "L2", "L3")),
                shot.apply("S01", List.of("L4")),
                shot.apply("S02", List.of("L5")))), onScreen);

        assertThat(repaired.shots()).extracting(ShotPlan.Shot::lineIds)
                .containsExactly(List.of("L1"), List.of("L2", "L3"), List.of("L4"), List.of("L5"))
                .as("L4 (3 s) can't join L2+L3 (8 s) without passing 10 s, so it stays");
        assertThat(repaired.shots()).extracting(ShotPlan.Shot::id).containsExactly("SH01", "SH02", "SH03", "SH04");
    }
}

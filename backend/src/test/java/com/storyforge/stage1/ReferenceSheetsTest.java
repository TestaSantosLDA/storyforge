package com.storyforge.stage1;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import com.storyforge.character.CharacterStore;
import com.storyforge.image.FakeImageEngine;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.TopicService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/** Stage 1 reference sheets: front first, side and expressions from it, Claude vision QA, kept on approval. */
@TestPropertySource(properties = "storyforge.images.engine=fake")
class ReferenceSheetsTest extends DbTest {

    @Autowired FakeLlmEngine claude;
    @Autowired FakeImageEngine images;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryRepository stories;
    @Autowired StoryStatusService status;
    @Autowired StoryStep storyStep;
    @Autowired Gates gates;
    @Autowired Drafts drafts;
    @Autowired CharacterStore characters;
    @Autowired AssetStorage assets;

    long id;

    @BeforeEach
    void setUp() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        Files.createDirectories(Path.of("target/test-characters"));
        claude.reset();
        images.reset();
        claude.byDefault("story", Stage1ScenariosTest.story("pip-the-hedgehog", "Pip", "am_puck"))
                .byDefault("story-qa", Stage1ScenariosTest.qaPass("structure"))
                .byDefault("sheet-qa", Stage1ScenariosTest.qaPass("anatomy", "matches_description"));
        var t = topics.create("Whisperwood", "A misty wood.", "user:t");
        id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), "user:t").getId();
        queue.run("user:t");
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    @Test
    void sheet_is_front_first_then_side_and_expressions_from_it_and_kept_on_approval() {
        storyStep.run(id);

        assertThat(story().getStatus()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(images.requests).hasSize(3);
        assertThat(images.requests.get(0).references()).isEmpty();
        assertThat(images.requests.get(0).prompt()).contains("three-quarter front view", "small round hedgehog", "storybook").doesNotContain("single tail");
        assertThat(images.requests.get(1).references()).hasSize(1);
        assertThat(images.requests.get(2).references()).hasSize(1);
        assertThat(claude.requests("sheet-qa").getFirst().images()).hasSize(3);
        assertThat(claude.requests("sheet-qa").getFirst().userPrompt()).contains("front.png", "anatomy");

        gates.approveStory(id, "user:t");

        var pip = characters.get("pip-the-hedgehog").orElseThrow();
        assertThat(pip.referenceImages()).containsExactly("characters/pip-the-hedgehog/front.png",
                "characters/pip-the-hedgehog/side.png", "characters/pip-the-hedgehog/expressions.png");
        assertThat(pip.referenceImages()).allMatch(assets::exists);
    }

    @Test
    void failed_sheet_is_redrawn_with_a_new_seed_without_using_the_story_qa_budget() {
        claude.then("sheet-qa", Stage1ScenariosTest.qaFail("anatomy", "the fox has two tails"));

        storyStep.run(id);

        assertThat(story().getStatus()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(story().getStoryQaRetries()).isZero();
        assertThat(images.requests).hasSize(6);
        assertThat(images.requests.get(3).prompt()).as("flaws are never named in the image prompt")
                .isEqualTo(images.requests.get(0).prompt()).doesNotContain("two tails");
        assertThat(images.requests.get(3).seed()).isNotEqualTo(images.requests.get(0).seed());
        assertThat(claude.requests("story")).as("the story text is not regenerated").hasSize(1);
    }

    @Test
    void sidecar_down_flags_without_a_strike_and_resume_only_redoes_the_sheet() {
        images.failNext = 1;

        storyStep.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Image sidecar unavailable");
        assertThat(story().getStoryQaRetries()).isZero();

        status.resume(id, "user:t", "sidecar started");
        storyStep.run(id);

        assertThat(story().getStatus()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(claude.requests("story")).as("story text kept").hasSize(1);
        assertThat(drafts.all(id, Drafts.Kind.STORY)).hasSize(1);
    }

    @Test
    void after_all_draws_fail_the_best_sheet_goes_to_gate_a_flagged_and_can_still_be_approved() {
        claude.then("sheet-qa", "{\"checks\":[{\"name\":\"anatomy\",\"pass\":false,\"feedback\":\"x\"},"
                        + "{\"name\":\"style\",\"pass\":false,\"feedback\":\"y\"}]}")
                .then("sheet-qa", Stage1ScenariosTest.qaFail("anatomy", "only one problem"))
                .then("sheet-qa", "{\"checks\":[{\"name\":\"anatomy\",\"pass\":false,\"feedback\":\"x\"},"
                        + "{\"name\":\"style\",\"pass\":false,\"feedback\":\"y\"}]}");

        storyStep.run(id);

        assertThat(story().getStatus()).isEqualTo(AWAITING_STORY_APPROVAL);
        assertThat(images.requests).hasSize(9);
        String sheets = drafts.latest(id, Drafts.Kind.STORY).orElseThrow().sheets();
        assertThat(sheets).contains("\"passed\": false", "\"attempts\": 3", "/a2/front.png").doesNotContain("/a1/", "/a3/");

        gates.approveStory(id, "user:t");
        assertThat(characters.get("pip-the-hedgehog").orElseThrow().referenceImages()).hasSize(3);
    }

    @Test
    void a_story_restored_after_archiving_starts_fresh_instead_of_reusing_its_old_draft() {
        claude.byDefault("sheet-qa", Stage1ScenariosTest.qaFail("anatomy", "two tails"));
        storyStep.run(id);
        gates.kill(id, Gates.Gate.STORY, "user:t");
        assertThat(story().getStatus()).isEqualTo(ARCHIVED);

        queue.restore(id, "user:t");
        queue.run("user:t");
        claude.byDefault("sheet-qa", Stage1ScenariosTest.qaPass("anatomy"));
        storyStep.run(id);

        assertThat(claude.requests("story")).as("story text regenerated after restore").hasSize(2);
        assertThat(drafts.all(id, Drafts.Kind.STORY)).extracting(Drafts.Draft::reviewState)
                .containsExactly("pending", "abandoned");
    }
}

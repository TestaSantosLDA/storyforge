package com.storyforge.stage5;

import static com.storyforge.story.StoryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.DbTest;
import com.storyforge.image.FakeImageEngine;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.notes.StoryNotes;
import com.storyforge.stage1.Gates;
import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.Stage1Fixtures;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioStep;
import com.storyforge.stage3.Stage3Fixtures;
import com.storyforge.stage3.VisualsStep;
import com.storyforge.stage4.FinalReview;
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
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/** The Stage 5 scenarios table (fake Claude, Flux, Kokoro and animator; the assembly row is written directly). */
@TestPropertySource(properties = "storyforge.images.engine=fake")
class Stage5ScenariosTest extends DbTest {

    static final String BY = "user:tester";
    static final String PIP = "pip-the-hedgehog";
    static final String TITLE = "Pip and the Lantern That Would Not Light | A Whisperwood Tale";

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
    @Autowired FinalReview review;
    @Autowired PublishStep publishStep;
    @Autowired Publishing publishing;
    @Autowired StoryNotes notes;
    @Autowired AssetStorage assets;

    long id;

    static String metadata(String title) {
        return """
                {"title":"%s","hook":"Pip finds a lantern that only glows when someone shares it.",
                 "summary":"A small hedgehog learns what light is for.","moral_line":"Light grows when you share it.",
                 "chapter_titles":["The lantern","The long night"],"tags":["bedtime story","family fable","hedgehog"],
                 "hashtags":["#bedtimestory","#fable"],
                 "thumbnails":[{"shot_id":"SH01","text":"The Lantern"},{"shot_id":"SH05","text":"Pip Shares"},
                               {"shot_id":"SH10","text":"Glow Together"}]}
                """.formatted(title);
    }

    @BeforeEach
    void storyAtFinalReview() throws Exception {
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
                .byDefault("shots", Stage3Fixtures.plan())
                .byDefault("shot-qa", Stage1Fixtures.qaPass("characters"))
                .byDefault("metadata", metadata(TITLE))
                .byDefault("metadata-qa", Stage1Fixtures.qaPass("honesty", "trademark", "thumbnails"));
        var t = topics.create("Whisperwood", "A misty wood.", BY);
        id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), BY).getId();
        queue.run(BY);
        storyStep.run(id);
        gates.approveStory(id, BY);
        scriptStep.run(id);
        gates.approveScript(id, BY);
        audioStep.run(id);
        visuals.run(id);
        assertThat(story().getStatus()).as(() -> story().getAttentionReason()).isEqualTo(ASSEMBLING);
        jdbc.sql("""
                insert into assembly (story_id, version, video_key, srt_key, duration_sec, intro_sec, music, qa, passed)
                values (:s, 1, 'stories/x/final.mp4', 'stories/x/final.srt', 230, 4, '[]', '[]', true)
                """).param("s", id).update();
        status.transition(id, ASSEMBLING, AWAITING_FINAL_REVIEW, "test", "assembled");
        review.approve(id, BY);
        assertThat(story().getStatus()).isEqualTo(PREPARING_PUBLISH);
    }

    Story story() {
        return stories.findById(id).orElseThrow();
    }

    @Test
    void s1_package_prepared_then_marked_published_with_the_url() throws Exception {
        publishStep.run(id);

        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);
        Publishing.Package p = publishing.latest(id).orElseThrow();
        assertThat(p.passed()).isTrue();
        assertThat(p.metadata().title()).isEqualTo(TITLE);
        assertThat(p.metadata().description())
                .startsWith("Pip finds a lantern")
                .contains("Light grows when you share it.")
                .contains("#bedtimestory #fable");
        assertThat(p.thumbnails()).hasSize(3).extracting(PublishStep.Thumbnail::shotId)
                .containsExactly("SH01", "SH05", "SH10");
        for (PublishStep.Thumbnail th : p.thumbnails()) {
            var img = ImageIO.read(assets.localPath(th.assetKey()).orElseThrow().toFile());
            assertThat(img.getWidth()).isEqualTo(Thumbnails.WIDTH);
            assertThat(img.getHeight()).isEqualTo(Thumbnails.HEIGHT);
        }
        assertThat(claude.requests("metadata-qa")).hasSize(1);

        publishing.markPublished(id, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", BY);

        assertThat(story().getStatus()).isEqualTo(PUBLISHED);
        assertThat(publishing.publishedUrl(id)).contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    }

    @Test
    void chapters_one_per_scene_from_0_00_and_short_scenes_merge_and_too_few_means_none() {
        var script = new com.storyforge.stage1.ScriptContent(null, List.of(
                scene("S01", "L001"), scene("S02", "L010"), scene("S03", "L011"), scene("S04", "L020")));
        Map<String, double[]> timing = Map.of("L001", new double[] {0, 5}, "L010", new double[] {50, 55},
                "L011", new double[] {56, 60}, "L020", new double[] {125, 130});

        var chapters = PublishStep.chapters(script, timing, 4, List.of("Start", "Middle", "Short", "End"));

        assertThat(chapters).extracting(PublishStep.Metadata.Chapter::time).containsExactly("0:00", "0:54", "2:09");
        assertThat(chapters).extracting(PublishStep.Metadata.Chapter::title).containsExactly("Start", "Middle", "End");
        assertThat(PublishStep.chapters(new com.storyforge.stage1.ScriptContent(null,
                List.of(scene("S01", "L001"), scene("S02", "L010"))), timing, 4, List.of("a", "b"))).isEmpty();
    }

    static com.storyforge.stage1.ScriptContent.Scene scene(String id, String firstLine) {
        return new com.storyforge.stage1.ScriptContent.Scene(id, "A clearing.", "calm", List.of(firstLine));
    }

    @Test
    void s2_edit_on_the_page_saves_a_new_version_and_regenerate_runs_again_with_the_notes() {
        publishStep.run(id);

        publishing.edit(id, "Pip's Lantern: A Gentle Story About Sharing Light", "New description", "fable, hedgehog");
        Publishing.Package edited = publishing.latest(id).orElseThrow();
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.metadata().title()).isEqualTo("Pip's Lantern: A Gentle Story About Sharing Light");
        assertThat(edited.metadata().tags()).containsExactly("fable", "hedgehog");
        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);

        publishing.regenerate(id, "Make the title warmer", BY);
        assertThat(story().getStatus()).isEqualTo(PREPARING_PUBLISH);
        publishStep.run(id);

        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);
        assertThat(publishing.latest(id).orElseThrow().version()).isEqualTo(3);
        assertThat(claude.requests("metadata").getLast().userPrompt()).contains("Make the title warmer");
    }

    @Test
    void s3_music_claim_disputed_stays_ready_or_sent_back_with_the_track_excluded() {
        publishStep.run(id);

        publishing.musicClaimed(id, 42, true, BY);
        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);

        publishing.musicClaimed(id, 42, false, BY);
        assertThat(story().getStatus()).isEqualTo(ASSEMBLING);
        assertThat(notes.get(id, "music-exclude")).contains("42");
        assertThat(jdbc.sql("select resolution from copyright_claim where story_id = :s order by id").param("s", id)
                .query(String.class).list()).containsExactly("disputed", "replaced");
    }

    @Test
    void s4_visual_claim_goes_to_rejection_analysis_with_the_claim_as_the_reason() {
        publishStep.run(id);

        publishing.visualClaim(id, "Matched third-party content at 1:12", BY);

        assertThat(story().getStatus()).isEqualTo(ANALYZING_REJECTION);
        assertThat(jdbc.sql("select reason from final_rejection where story_id = :s").param("s", id)
                .query(String.class).single()).contains("Matched third-party content at 1:12");
    }

    @Test
    void s5_metadata_failing_qa_three_times_flags() {
        claude.byDefault("metadata", metadata("Too short"));

        publishStep.run(id);

        assertThat(story().getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(story().getAttentionReason()).contains("Metadata QA failed 3 times").contains("title");
        assertThat(claude.requests("metadata")).hasSize(3);
        assertThat(claude.requests("metadata").getLast().userPrompt()).contains("Fix these problems");
    }

    @Test
    void a_failing_honesty_check_retries_with_the_feedback() {
        claude.then("metadata-qa", "{\"checks\":[{\"name\":\"honesty\",\"pass\":false,\"feedback\":\"The thumbnail promises a dragon.\"}]}");

        publishStep.run(id);

        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);
        assertThat(claude.requests("metadata")).hasSize(2);
        assertThat(claude.requests("metadata").getLast().userPrompt()).contains("promises a dragon");
    }

    @Test
    void s6_a_url_that_is_not_a_youtube_video_is_refused() {
        publishStep.run(id);

        for (String bad : List.of("https://example.com/watch?v=dQw4w9WgXcQ", "youtube.com", "https://youtu.be/short")) {
            assertThatThrownBy(() -> publishing.markPublished(id, bad, BY)).isInstanceOf(InvalidInputException.class);
        }
        assertThat(story().getStatus()).isEqualTo(READY_TO_UPLOAD);
        publishing.markPublished(id, "https://youtu.be/dQw4w9WgXcQ", BY);
        assertThat(story().getStatus()).isEqualTo(PUBLISHED);
    }

    @Test
    void s8_kill_from_the_publish_screen_archives() {
        publishStep.run(id);

        publishing.kill(id, BY);

        assertThat(story().getStatus()).isEqualTo(ARCHIVED);
    }
}

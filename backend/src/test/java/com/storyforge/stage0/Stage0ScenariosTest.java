package com.storyforge.stage0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.DbTest;
import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.story.RunResult;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicInUseException;
import com.storyforge.topic.TopicRepository;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileSystemUtils;

/** The Stage 0 scenarios table, one test per row (docs/pipeline-a/stage-0 "Scenarios"). */
class Stage0ScenariosTest extends DbTest {

    static final String BY = "user:tester";

    @Autowired
    TopicService topicService;
    @Autowired
    StoryQueueService queue;
    @Autowired
    StoryStatusService status;
    @Autowired
    StoryRepository stories;
    @Autowired
    TopicRepository topics;
    @Autowired
    CharacterStore characters;

    @BeforeEach
    void noCharacters() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
    }

    Topic topic(String name) {
        return topicService.create(name, "A misty wood where small animals learn big lessons.", BY);
    }

    Story add(Topic t, String concept) {
        return queue.add(new StoryForm(t.getId(), concept, "Listening matters", 4, null), BY);
    }

    List<String> queueOrder() {
        return queue.queue().stream().map(Story::getConcept).toList();
    }

    @Test
    void s1_create_topic_add_story_drag_to_top_and_run() {
        Topic t = topic("Whisperwood");
        add(t, "a");
        add(t, "b");
        Story c = add(t, "c");

        queue.reorder(c.getId(), 1, BY);
        assertThat(queueOrder()).containsExactly("c", "a", "b");

        assertThat(queue.run(BY)).isEqualTo(new RunResult.Started(c.getId()));
        assertThat(stories.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(StoryStatus.STORY_IN_PROGRESS);
        assertThat(queueOrder()).containsExactly("a", "b");
    }

    @Test
    void s2_story_in_a_brand_new_topic_with_no_characters_is_allowed() {
        Story s = add(topic("Empty Meadow"), "first story");
        assertThat(s.getStatus()).isEqualTo(StoryStatus.QUEUED);
    }

    @Test
    void s3_missing_required_fields_are_rejected_with_field_messages_and_nothing_saved() {
        Topic t = topic("Whisperwood");

        assertThatThrownBy(() -> queue.add(new StoryForm(t.getId(), " ", "", 7, null), BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsOnlyKeys("concept", "moral", "targetLengthMin"));
        assertThatThrownBy(() -> topicService.create("", "", BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsOnlyKeys("name", "description"));
        assertThatThrownBy(() -> topicService.create("whisperwood", "dup", BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsKey("name"));
        assertThat(stories.count()).isZero();
        assertThat(topics.count()).isEqualTo(1);
    }

    @Test
    void s4_a_started_story_cannot_be_edited_deleted_or_reordered() {
        Topic t = topic("Whisperwood");
        Story s = add(t, "started");
        queue.run(BY);
        StoryForm edit = new StoryForm(t.getId(), "changed", "m", 4, null);

        assertThatThrownBy(() -> queue.edit(s.getId(), edit, BY)).isInstanceOf(StatusConflictException.class);
        assertThatThrownBy(() -> queue.delete(s.getId(), BY)).isInstanceOf(StatusConflictException.class);
        assertThatThrownBy(() -> queue.reorder(s.getId(), 1, BY)).isInstanceOf(StatusConflictException.class);
        assertThat(stories.findById(s.getId()).orElseThrow().getConcept()).isEqualTo("started");
    }

    @Test
    void s4b_a_queued_story_can_be_edited_and_deleting_closes_the_gap() {
        Topic t = topic("Whisperwood");
        Story a = add(t, "a");
        Story b = add(t, "b");
        Story c = add(t, "c");

        queue.edit(a.getId(), new StoryForm(t.getId(), "a2", "new moral", 5, "note"), BY);
        queue.delete(b.getId(), BY);

        assertThat(queueOrder()).containsExactly("a2", "c");
        assertThat(stories.findById(c.getId()).orElseThrow().getQueuePosition()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from entity_change where entity_type = 'story'").query(Long.class)
                .single()).as("create x3, edit, delete logged").isEqualTo(5);
    }

    @Test
    void s5_topic_edit_while_a_story_is_in_flight_keeps_its_snapshot() {
        Topic t = topic("Whisperwood");
        Story running = add(t, "running");
        queue.run(BY);

        topicService.editDescription(t.getId(), "A sunny beach now.", BY);
        Story later = add(t, "later");

        assertThat(stories.findById(running.getId()).orElseThrow().getTopicSnapshot())
                .isEqualTo("A misty wood where small animals learn big lessons.");
        assertThat(topics.findById(t.getId()).orElseThrow().getDescription()).isEqualTo("A sunny beach now.");
        assertThat(later.getTopicSnapshot()).as("snapshot is taken at Run, not at add").isNull();
    }

    @Test
    void s6_topic_with_started_stories_or_characters_cannot_be_deleted() throws Exception {
        Topic started = topic("Started");
        add(started, "x");
        queue.run(BY);
        assertThatThrownBy(() -> topicService.delete(started.getId(), BY)).isInstanceOf(TopicInUseException.class);

        Topic withCast = topic("With cast");
        Files.createDirectories(Path.of("target/test-characters"));
        characters.create(new CharacterFile("pip", "Pip", withCast.getId(), CharacterFile.Kind.RECURRING,
                "p", "v", List.of(), null, null));
        assertThatThrownBy(() -> topicService.delete(withCast.getId(), BY)).isInstanceOf(TopicInUseException.class);

        Topic onlyQueued = topic("Only queued");
        add(onlyQueued, "q1");
        add(onlyQueued, "q2");
        topicService.delete(onlyQueued.getId(), BY);
        assertThat(topics.findById(onlyQueued.getId())).isEmpty();
        assertThat(stories.findByTopicId(onlyQueued.getId())).isEmpty();
    }

    @Test
    void s6b_a_restored_story_still_counts_as_started_for_topic_deletion() {
        Topic t = topic("Restored");
        Story s = add(t, "x");
        queue.run(BY);
        status.archive(s.getId(), StoryStatus.STORY_IN_PROGRESS, com.storyforge.story.ArchiveReason.KILLED, BY, null);
        queue.restore(s.getId(), BY);

        assertThatThrownBy(() -> topicService.delete(t.getId(), BY)).isInstanceOf(TopicInUseException.class);
    }

    @Test
    void s7_run_with_an_empty_queue() {
        assertThat(queue.run(BY)).isInstanceOf(RunResult.NothingToRun.class);
    }

    @Test
    void s10_run_at_the_cap_is_refused() {
        Topic t = topic("Whisperwood");
        add(t, "a");
        add(t, "b");
        queue.run(BY);
        assertThat(queue.run(BY)).isEqualTo(new RunResult.AtCapacity(1, 1));
    }

    @Test
    void s11_restore_goes_to_the_bottom_with_counters_reset() {
        Topic t = topic("Whisperwood");
        Story s = add(t, "x");
        add(t, "y");
        queue.run(BY);
        status.archive(s.getId(), StoryStatus.STORY_IN_PROGRESS, com.storyforge.story.ArchiveReason.KILLED, BY, null);

        queue.restore(s.getId(), BY);

        assertThat(queueOrder()).containsExactly("y", "x");
    }

    @Test
    void reorder_beyond_the_ends_clamps() {
        Topic t = topic("Whisperwood");
        Story a = add(t, "a");
        add(t, "b");
        queue.reorder(a.getId(), 99, BY);
        assertThat(queueOrder()).containsExactly("b", "a");
        queue.reorder(a.getId(), -3, BY);
        assertThat(queueOrder()).containsExactly("a", "b");
    }
}

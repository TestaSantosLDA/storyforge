package com.storyforge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.DbTest;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.RunResult;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryFormat;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/**
 * Pipeline D profile scenarios 2, 2b and 2c (docs/pipeline-d/README.md "Scenarios"), plus the snapshot rules around
 * Run and restore. Profiles are copied into a scratch directory so a test can edit a file after Run.
 */
@TestPropertySource(properties = "storyforge.paths.profiles=target/test-profiles")
class ProfileScenariosTest extends DbTest {

    static final String BY = "user:tester";
    static final Path PROFILES = Path.of("target/test-profiles");

    @Autowired
    TopicService topics;
    @Autowired
    StoryQueueService queue;
    @Autowired
    StoryStatusService status;
    @Autowired
    StoryRepository stories;

    @BeforeEach
    void copyRepoProfiles() throws Exception {
        FileSystemUtils.deleteRecursively(PROFILES);
        FileSystemUtils.copyRecursively(Path.of("../channel/video-profiles"), PROFILES);
    }

    Topic topic(String name) {
        return topics.create(name, "A world.", BY);
    }

    Story add(Topic t, String lookNotes) {
        return queue.add(new StoryForm(t.getId(), "concept", "moral", 4, null, lookNotes), BY);
    }

    Map<String, Object> snapshotOf(Story s) {
        return ProfileSnapshots.fromJson(stories.findById(s.getId()).orElseThrow().getProfileSnapshot());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void s2_profile_and_override_are_snapshotted_at_run_and_later_edits_do_not_change_the_story() throws Exception {
        Topic t = topic("Old maps");
        topics.setProfile(t.getId(), "chronicle", "narrator_voice: am_adam", null, BY);
        Story s = add(t, null);
        assertThat(stories.findById(s.getId()).orElseThrow().getProfileSnapshot()).isNull();

        assertThat(queue.run(BY)).isEqualTo(new RunResult.Started(s.getId()));
        Map<String, Object> snap = snapshotOf(s);
        assertThat(snap).containsEntry("profile_id", "chronicle").containsEntry("profile_version", 1)
                .containsEntry("overrides", Map.of("narrator_voice", "am_adam"));
        assertThat(map(snap.get("profile"))).containsEntry("narrator_voice", "am_adam")
                .containsEntry("frame", "vertical");
        assertThat((String) snap.get("profile_file_sha256")).hasSize(64);

        Path file = PROFILES.resolve("chronicle.yaml");
        Files.writeString(file, Files.readString(file).replace("version: 1", "version: 2")
                .replace("grave and documentary", "light and breezy"));
        topics.setProfile(t.getId(), "chronicle", "narrator_voice: bf_emma", null, BY);

        assertThat(snapshotOf(s)).isEqualTo(snap);
    }

    @Test
    void s2b_topic_and_story_look_notes_both_reach_the_snapshot() {
        Topic t = topic("Chalk");
        topics.setProfile(t.getId(), "explainer", null, "chalkboard look, white chalk lines on dark green", BY);
        Story s = add(t, "open on a big number");
        queue.run(BY);

        Map<String, Object> notes = map(snapshotOf(s).get("look_notes"));
        assertThat(notes).containsEntry("topic", "chalkboard look, white chalk lines on dark green")
                .containsEntry("story", "open on a big number")
                .containsEntry("combined", "chalkboard look, white chalk lines on dark green\n\nopen on a big number");
    }

    @Test
    void s2c_look_notes_naming_a_studio_are_refused_when_saved() {
        Topic t = topic("Copycat");
        assertThatThrownBy(() -> topics.setProfile(t.getId(), "tale", null, "make it feel like Pixar", BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors().get("lookNotes")).contains("Pixar"));
        assertThat(topics.all().getFirst().getProfileId()).isNull();

        topics.setProfile(t.getId(), "tale", null, null, BY);
        assertThatThrownBy(() -> add(t, "a Studio Ghibli feel"))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors().get("lookNotes")).contains("Studio Ghibli"));
    }

    @Test
    void epic_topics_run_as_landscape_long_form() {
        Topic t = topic("Os Lusíadas — Remixed");
        topics.setProfile(t.getId(), "epic", null, null, BY);
        Story s = add(t, null);
        queue.run(BY);

        Map<String, Object> profile = map(snapshotOf(s).get("profile"));
        assertThat(profile).containsEntry("frame", "landscape").containsEntry("illustrations", "none")
                .containsEntry("opening", "title_sting");
        assertThat(map(profile.get("target_seconds"))).containsEntry("target", 540.0).containsEntry("min", 480.0);
    }

    @Test
    void a_fable_topic_has_no_profile_snapshot() {
        Topic t = topic("Whisperwood");
        Story s = add(t, null);
        queue.run(BY);

        Story ran = stories.findById(s.getId()).orElseThrow();
        assertThat(ran.getProfileSnapshot()).isNull();
        assertThat(ran.getTopicSnapshot()).isEqualTo("A world.");
    }

    @Test
    void a_topic_with_a_profile_makes_motion_pictures_and_one_without_makes_fables() {
        Topic motion = topic("Maps");
        topics.setProfile(motion.getId(), "chronicle", null, null, BY);
        Topic fable = topic("Wood");

        assertThat(add(motion, null).getFormat()).isEqualTo(StoryFormat.MOTION_PICTURE);
        assertThat(add(fable, null).getFormat()).isEqualTo(StoryFormat.FABLE);
    }

    @Test
    void a_motion_picture_whose_topic_lost_its_profile_is_refused_at_run() {
        Topic t = topic("Fickle");
        topics.setProfile(t.getId(), "tale", null, null, BY);
        Story s = add(t, null);
        topics.setProfile(t.getId(), "", null, null, BY);

        assertThatThrownBy(() -> queue.run(BY)).isInstanceOf(ProfileException.class)
                .hasMessageContaining("has no video profile");
        assertThat(stories.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(StoryStatus.QUEUED);
    }

    @Test
    void bad_overrides_and_unknown_profiles_are_refused_when_saved() {
        Topic t = topic("Bad");
        assertThatThrownBy(() -> topics.setProfile(t.getId(), "chronicle", "frame: landscape", null, BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors().get("overrides")).contains("480 s"));
        assertThatThrownBy(() -> topics.setProfile(t.getId(), "nope", null, null, BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("profileId"));
        assertThatThrownBy(() -> topics.setProfile(t.getId(), "", "music: false", null, BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors().get("overrides")).contains("Pick a profile"));
    }

    @Test
    void run_is_refused_and_the_story_stays_queued_when_the_profile_file_is_gone() throws Exception {
        Topic t = topic("Vanishing");
        topics.setProfile(t.getId(), "tale", null, null, BY);
        Story s = add(t, null);
        Files.delete(PROFILES.resolve("tale.yaml"));

        assertThatThrownBy(() -> queue.run(BY)).isInstanceOf(ProfileException.class)
                .hasMessageContaining("no profile file named tale");
        Story still = stories.findById(s.getId()).orElseThrow();
        assertThat(still.getStatus()).isEqualTo(StoryStatus.QUEUED);
        assertThat(still.getQueuePosition()).isNotNull();
    }

    @Test
    void restore_clears_the_snapshot_so_the_next_run_takes_a_fresh_one() {
        Topic t = topic("Again");
        topics.setProfile(t.getId(), "explainer", null, null, BY);
        Story s = add(t, null);
        queue.run(BY);
        status.archive(s.getId(), StoryStatus.STORY_IN_PROGRESS, ArchiveReason.KILLED, BY, null);
        queue.restore(s.getId(), BY);

        assertThat(stories.findById(s.getId()).orElseThrow().getProfileSnapshot()).isNull();
    }
}

package com.storyforge.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storyforge.DbTest;
import com.storyforge.story.RunResult;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Series scenarios that the data layer owns (docs/series/lusiadas.md "Scenarios" 2 and 13, decisions C2, C5 and C9).
 * The source text is a synthetic copy with the real stanza counts, written by {@link SourceFixture}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "storyforge.paths.sources=target/test-sources")
class SeriesScenariosTest extends DbTest {

    static final String BY = "user:tester";

    @Autowired
    TopicService topics;
    @Autowired
    StoryQueueService queue;
    @Autowired
    StoryRepository stories;
    @Autowired
    ChannelService channels;
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void sources() throws Exception {
        SourceFixture.write(Path.of("target/test-sources"));
    }

    Topic seriesTopic() {
        Topic t = topics.create("Os Lusíadas — Remixed", "The epic, remixed.", BY);
        return topics.setSeries(t.getId(), channels.byDefault().getId(), true, "lusiadas", "Adamastor\nVenus", BY);
    }

    StoryForm episode(Topic t, Integer season, Integer number, String excerpt) {
        return new StoryForm(t.getId(), "The Gods Have a Group Chat", "moral", 4, null, null, season, number, excerpt,
                false);
    }

    @Test
    void new_topics_start_on_the_default_fables_channel() {
        Topic t = topics.create("Whisperwood", "A misty wood.", BY);
        assertThat(t.getChannel().getName()).isEqualTo("Fables");
        assertThat(t.getChannel().getClosingLineText()).isEqualTo("Watch the rest of the episode on our channel!");
    }

    @Test
    void the_story_captures_its_channel_at_run_and_later_channel_edits_do_not_change_it() throws Exception {
        Channel epic = channels.create(new ChannelForm("Lusíadas Remixed", "@lusiadasremixed", null, null, "am_adam",
                "Watch the full episode on our channel!", false, "epic, playful"), BY);
        Topic t = seriesTopic();
        topics.setSeries(t.getId(), epic.getId(), true, "lusiadas", null, BY);
        Story s = queue.add(episode(t, 1, 1, "I 19-41"), BY);
        assertThat(queue.run(BY)).isInstanceOf(RunResult.Started.class);

        String snap = stories.findById(s.getId()).orElseThrow().getChannelSnapshot();
        var json = JsonMapper.builder().build().readTree(snap);
        assertThat(json.path("name").asString()).isEqualTo("Lusíadas Remixed");
        assertThat(json.path("youtube_handle").asString()).isEqualTo("@lusiadasremixed");
        assertThat(json.path("music_moods").toString()).isEqualTo("[\"epic\",\"playful\"]");

        channels.edit(epic.getId(), new ChannelForm("Renamed", null, null, null, null, null, false, null), BY);
        assertThat(stories.findById(s.getId()).orElseThrow().getChannelSnapshot()).isEqualTo(snap);
    }

    @Test
    void channel_names_are_unique_and_handles_are_checked() {
        assertThatThrownBy(() -> channels.create(new ChannelForm("fables", "nohandle", null, null, null, null, false,
                null), BY)).isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                .containsKey("youtubeHandle"));
        assertThatThrownBy(() -> channels.create(new ChannelForm("FABLES", null, null, null, null, null, false, null), BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("name"));
    }

    @Test
    void s2_an_episode_without_a_source_excerpt_is_refused_with_the_reason() {
        Topic t = seriesTopic();
        assertThatThrownBy(() -> queue.add(episode(t, 1, 1, ""), BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("sourceExcerpt", "Give the stanzas this episode covers, e.g. \"I 19-41\"."));
        assertThat(stories.findAll()).isEmpty();
    }

    @Test
    void the_excerpt_must_lie_inside_the_poem_and_is_stored_as_canto_and_stanza_ranges() {
        Topic t = seriesTopic();
        assertThatThrownBy(() -> queue.add(episode(t, 1, 1, "I 100-120"), BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("sourceExcerpt", "Canto I has stanzas 1 to 106."));
        assertThatThrownBy(() -> queue.add(episode(t, 1, 1, "XI 1"), BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("sourceExcerpt", "Os Lusíadas has cantos I to X."));

        Story s = queue.add(episode(t, 1, 2, "I 106; ii 1-30"), BY);
        Story stored = stories.findById(s.getId()).orElseThrow();
        assertThat(SourceExcerpt.fromJson(stored.getSourceExcerpt()).ranges()).containsExactly(
                new SourceExcerpt.Range(1, 106, 106), new SourceExcerpt.Range(2, 1, 30));
        assertThat(stored.getSourceExcerpt()).contains("\"from_stanza\"", "\"to_stanza\"");
        assertThat(stored.getSourceExcerptLabel()).isEqualTo("Canto I, st. 106; Canto II, st. 1–30");
        assertThat(stored.getEpisodeLabel()).isEqualTo("S1 · Ep.02");
        assertThat(StoryForm.of(stored).sourceExcerpt()).isEqualTo("I 106; II 1-30");
    }

    @Test
    void episode_numbers_are_required_and_unique_per_topic_and_season() {
        Topic t = seriesTopic();
        assertThatThrownBy(() -> queue.add(episode(t, 1, null, "I 1-18"), BY))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("episodeNumber"));
        Story first = queue.add(episode(t, 1, 1, "I 1-18"), BY);
        assertThatThrownBy(() -> queue.add(episode(t, 1, 1, "I 19-41"), BY))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("episodeNumber", "Season 1 already has an episode 1: \"The Gods Have a Group Chat\"."));
        assertThat(queue.add(episode(t, 2, 1, "I 19-41"), BY).getEpisodeLabel()).isEqualTo("S2 · Ep.01");
        // Editing an episode keeps its own number without tripping the uniqueness check.
        assertThat(queue.edit(first.getId(), episode(t, 1, 1, "I 1-20"), BY).getSourceExcerptLabel())
                .isEqualTo("Canto I, st. 1–20");
    }

    @Test
    void a_topic_outside_a_series_ignores_episode_fields() {
        Topic t = topics.create("Whisperwood", "A misty wood.", BY);
        Story s = queue.add(episode(t, 1, 3, "nonsense"), BY);
        assertThat(s.getEpisodeNumber()).isNull();
        assertThat(s.getSourceExcerpt()).isNull();
    }

    @Test
    void run_asks_first_when_the_previous_episode_is_not_final_approved() throws Exception {
        Topic t = seriesTopic();
        Story ep2 = queue.add(episode(t, 1, 2, "I 42-60"), BY);
        assertThat(queue.runWarning()).contains("S1 · Ep.02 is next, but there is no episode 1 in season 1 yet.");

        Story ep1 = queue.add(episode(t, 1, 1, "I 19-41"), BY);
        assertThat(queue.runWarning()).contains("S1 · Ep.02 is next, but episode 1 is still queued, not final-approved.");

        mvc.perform(post("/queue/run")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("confirm")));
        assertThat(stories.findById(ep2.getId()).orElseThrow().getQueuePosition()).isEqualTo(1);

        // Published (past final_approved), so it is not in flight and doesn't block Run on capacity.
        jdbc.sql("update story set status = 'published', queue_position = null where id = ?").param(ep1.getId())
                .update();
        assertThat(queue.runWarning()).isEmpty();
        mvc.perform(post("/queue/run").param("confirm", "false")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", org.hamcrest.Matchers.startsWith("Started")));
    }

    @Test
    void a_confirmed_run_starts_the_episode_anyway() throws Exception {
        Topic t = seriesTopic();
        Story ep3 = queue.add(episode(t, 1, 3, "I 42-60"), BY);
        mvc.perform(post("/queue/run").param("confirm", "true")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", org.hamcrest.Matchers.startsWith("Started")));
        assertThat(stories.findById(ep3.getId()).orElseThrow().getQueuePosition()).isNull();
    }

    @Test
    void c9_the_allowlist_is_stored_and_may_not_contain_a_studio_or_franchise() {
        Topic t = seriesTopic();
        assertThat(t.getNameAllowlist()).isEqualTo("[\"Adamastor\",\"Venus\"]");
        assertThatThrownBy(() -> topics.setSeries(t.getId(), channels.byDefault().getId(), true, "lusiadas",
                "Adamastor\nPixar", BY)).isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("nameAllowlist"));
        assertThatThrownBy(() -> topics.setSeries(t.getId(), channels.byDefault().getId(), true, "no-such-source",
                null, BY)).isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("sourceId"));
    }

    @Test
    void the_new_pages_render() throws Exception {
        Topic t = seriesTopic();
        Story s = queue.add(episode(t, 1, 1, "I 19-41"), BY);
        for (String url : List.of("/channels", "/topics/" + t.getId(), "/stories/" + s.getId(),
                "/stories/" + s.getId() + "/edit", "/stories/new?topicId=" + t.getId(), "/queue")) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        mvc.perform(post("/channels").param("name", "Lusíadas Remixed").param("youtubeHandle", "@lusiadas"))
                .andExpect(status().is3xxRedirection());
        assertThat(channels.all()).extracting(Channel::getName).contains("Lusíadas Remixed");
    }
}

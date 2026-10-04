package com.storyforge.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storyforge.DbTest;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Every page renders, and the page actions call through to the same rules the services enforce. */
@AutoConfigureMockMvc
class PagesTest extends DbTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TopicService topics;
    @Autowired
    StoryQueueService queue;

    Topic topic() {
        return topics.create("Whisperwood", "A misty wood.", "user:t");
    }

    Story story(Topic t, String concept) {
        return queue.add(new StoryForm(t.getId(), concept, "moral", 4, null), "user:t");
    }

    @Test
    void empty_queue_page_has_run_disabled() throws Exception {
        mvc.perform(get("/queue")).andExpect(status().isOk())
                .andExpect(content().string(containsString("The queue is empty")))
                .andExpect(content().string(containsString("Create a <a href=\"/topics\">topic</a> first")))
                .andExpect(content().string(containsString("disabled")));
    }

    @Test
    void every_page_renders() throws Exception {
        Topic t = topic();
        Story s = story(t, "The fox who would not listen");
        for (String url : new String[] {"/queue", "/topics", "/topics/" + t.getId(), "/stories/" + s.getId(),
                "/stories/" + s.getId() + "/edit", "/stories/new?topicId=" + t.getId(), "/views/in-progress",
                "/views/awaiting", "/views/attention", "/views/published", "/views/archive"}) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        mvc.perform(get("/views/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/stories/999")).andExpect(status().isNotFound());
    }

    @Test
    void add_story_form_shows_field_errors_and_saves_nothing() throws Exception {
        Topic t = topic();
        mvc.perform(post("/stories").param("topicId", t.getId().toString()).param("concept", "")
                        .param("moral", "").param("targetLengthMin", "9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Describe the story idea.")))
                .andExpect(content().string(containsString("Target length must be 3 to 5 minutes.")));
        mvc.perform(get("/queue")).andExpect(content().string(containsString("The queue is empty")));
    }

    @Test
    void run_button_starts_the_top_story() throws Exception {
        Topic t = topic();
        story(t, "Top story");
        mvc.perform(post("/queue/run")).andExpect(redirectedUrl("/queue"))
                .andExpect(flash().attribute("notice", "Started: Top story"));
        mvc.perform(post("/queue/run")).andExpect(flash().attribute("error", "Already 1 in flight (limit 1)."));
    }

    @Test
    void reorder_endpoint_returns_the_stored_order_and_refuses_started_stories() throws Exception {
        Topic t = topic();
        Story a = story(t, "a");
        Story b = story(t, "b");
        mvc.perform(post("/queue/reorder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":" + b.getId() + ",\"position\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value(b.getId()))
                .andExpect(jsonPath("$.order[1]").value(a.getId()));

        mvc.perform(post("/queue/run"));
        mvc.perform(post("/queue/reorder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":" + b.getId() + ",\"position\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void editing_a_started_story_redirects_with_the_reason() throws Exception {
        Topic t = topic();
        Story s = story(t, "a");
        mvc.perform(post("/queue/run"));
        mvc.perform(get("/stories/" + s.getId()))
                .andExpect(content().string(containsString("can't be edited or deleted")))
                .andExpect(content().string(not(containsString(">Delete<"))));
        mvc.perform(post("/stories/" + s.getId() + "/delete").header("Referer", "http://localhost/stories/" + s.getId()))
                .andExpect(redirectedUrl("/stories/" + s.getId()))
                .andExpect(flash().attribute("error", containsString("has started")));
    }

    @Test
    void queue_state_changes_when_the_queue_changes() throws Exception {
        Topic t = topic();
        String before = mvc.perform(get("/queue/state")).andReturn().getResponse().getContentAsString();
        story(t, "new");
        mvc.perform(get("/queue/state")).andExpect(content().string(not(before)));
    }
}

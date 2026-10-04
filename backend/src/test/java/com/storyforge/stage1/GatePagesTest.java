package com.storyforge.stage1;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storyforge.DbTest;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.topic.TopicService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.util.FileSystemUtils;

/** Gate A and Gate B pages render real drafts and their buttons drive the gates. */
@AutoConfigureMockMvc
class GatePagesTest extends DbTest {

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired FakeLlmEngine claude;
    @Autowired TopicService topics;
    @Autowired StoryQueueService queue;
    @Autowired StoryStep storyStep;
    @Autowired ScriptStep scriptStep;

    @Test
    void review_story_then_script_through_the_pages() throws Exception {
        FileSystemUtils.deleteRecursively(Path.of("target/test-characters"));
        Files.createDirectories(Path.of("target/test-characters"));
        claude.reset();
        claude.byDefault("story", Stage1ScenariosTest.story("pip-the-hedgehog", "Pip", "am_puck"))
                .byDefault("story-qa", Stage1ScenariosTest.qaPass("structure", "moral"))
                .byDefault("script", Stage1ScenariosTest.script("pip-the-hedgehog", 40))
                .byDefault("script-qa", Stage1ScenariosTest.qaPass("story_fidelity"));
        var t = topics.create("Whisperwood", "A misty wood.", "user:t");
        long id = queue.add(new StoryForm(t.getId(), "c", "m", 4, null), "user:t").getId();
        queue.run("user:t");
        storyStep.run(id);

        mvc.perform(get("/stories/" + id + "/gate-a")).andExpect(status().isOk())
                .andExpect(content().string(containsString("The Quiet Clearing")))
                .andExpect(content().string(containsString("Pip")))
                .andExpect(content().string(containsString("Approve story and cast")));
        mvc.perform(post("/stories/" + id + "/gate-a/approve")).andExpect(redirectedUrl("/stories/" + id));

        scriptStep.run(id);
        mvc.perform(get("/stories/" + id + "/gate-b")).andExpect(status().isOk())
                .andExpect(content().string(containsString("520 words")))
                .andExpect(content().string(containsString("Narrator")))
                .andExpect(content().string(containsString("Pip")))
                .andExpect(content().string(containsString("Approve script")));
        mvc.perform(post("/stories/" + id + "/gate-b/regenerate").param("notes", ""))
                .andExpect(redirectedUrl("/stories/" + id + "/gate-b"));
        mvc.perform(post("/stories/" + id + "/gate-b/approve")).andExpect(redirectedUrl("/stories/" + id));
        mvc.perform(get("/stories/" + id)).andExpect(content().string(containsString("script approved")));
    }
}

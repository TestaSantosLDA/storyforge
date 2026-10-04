package com.storyforge.story;

import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Stage 0 scenarios 8 and 9: Run clicked many times at once never starts more than the cap. */
@TestPropertySource(properties = "storyforge.pipeline.max-concurrent-stories=2")
class RunConcurrencyTest extends DbTest {

    @Autowired
    StoryStatusService service;
    @Autowired
    StoryRepository stories;
    @Autowired
    TopicRepository topics;
    @Autowired
    StoryforgeProperties props;

    @Test
    void twenty_simultaneous_runs_start_exactly_cap_stories() throws Exception {
        Topic topic = topics.save(new Topic("T", "D"));
        for (int i = 0; i < 5; i++) {
            service.enqueue(topic, "concept " + i, "moral", 4, null, "user:t");
        }
        int clicks = 20;
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<RunResult>> tasks = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            tasks.add(() -> {
                go.await();
                return service.runNext("user:t");
            });
        }
        List<RunResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clicks)) {
            List<Future<RunResult>> futures = tasks.stream().map(pool::submit).toList();
            go.countDown();
            for (Future<RunResult> f : futures) {
                results.add(f.get());
            }
        }

        int cap = props.pipeline().maxConcurrentStories();
        assertThat(results).filteredOn(r -> r instanceof RunResult.Started).hasSize(cap);
        assertThat(stories.countByStatusIn(java.util.Set.of(StoryStatus.STORY_IN_PROGRESS))).isEqualTo(cap);
        // The first two in queue order were the ones started.
        assertThat(stories.findAll()).filteredOn(s -> s.getStatus() == StoryStatus.STORY_IN_PROGRESS)
                .extracting(Story::getConcept).containsExactlyInAnyOrder("concept 0", "concept 1");
    }
}

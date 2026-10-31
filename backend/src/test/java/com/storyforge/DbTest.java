package com.storyforge;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Base for tests against a real Postgres (Testcontainers). Each test starts from empty tables. */
@SpringBootTest(properties = {
        "storyforge.paths.characters=target/test-characters",
        "storyforge.paths.assets=target/test-assets",
        "storyforge.paths.prompts=../prompts",
        "storyforge.llm.engine=fake",
        "storyforge.images.engine=none",
        "storyforge.voices.engine=fake",
        "storyforge.clips.fetcher=fake",
        "storyforge.animation.engine=fake",
        "storyforge.paths.pronunciation=target/test-pronunciation.yaml",
        "storyforge.llm.first-backoff=1ms",
        "storyforge.pipeline.auto-dispatch=false",
        "storyforge.pipeline.auto-resume-check=1h"})
@Import({TestcontainersConfiguration.class, FakeLlmConfig.class})
public abstract class DbTest {

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("truncate entity_change, attempt, status_change, story, topic, music_track, clip_job, source_video restart identity cascade")
                .update();
        jdbc.sql("update gpu_lease set holder = null, acquired_at = null, expires_at = null").update();
        // Channels outlive topics; keep only the default (fables) channel, as V15 created it.
        jdbc.sql("delete from channel where not is_default").update();
        jdbc.sql("update channel set name = 'Fables', youtube_handle = null, tiktok_account = null, instagram_account = null, "
                + "narrator_voice = null, closing_line_text = 'Watch the rest of the episode on our channel!', "
                + "made_for_kids = false, music_moods = null").update();
    }
}

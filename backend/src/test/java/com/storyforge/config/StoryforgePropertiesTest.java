package com.storyforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class StoryforgePropertiesTest {

    @Test
    void relative_paths_bind_and_every_default_matches_claude_md() {
        var source = new MapConfigurationPropertySource(Map.of(
                "storyforge.paths.assets", "/tmp/assets",
                "storyforge.paths.characters", "../characters",
                "storyforge.paths.prompts", "../prompts"));
        StoryforgeProperties p = new Binder(source).bind("storyforge", StoryforgeProperties.class).get();

        assertThat(p.paths().charactersDir()).isEqualTo(Path.of("../characters"));
        assertThat(p.pipeline().maxConcurrentStories()).isEqualTo(1);
        assertThat(p.budgets().storyQaRetries()).isEqualTo(5);
        assertThat(p.budgets().scriptRejections()).isEqualTo(3);
        assertThat(p.audio().retriesPerLine()).isEqualTo(3);
        assertThat(p.audio().transcriptMatch()).isEqualTo(0.95);
        assertThat(p.audio().pauseSpeakerChange()).isEqualTo(Duration.ofMillis(600));
        assertThat(p.visuals().maxCharactersPerShot()).isEqualTo(4);
        assertThat(p.visuals().referenceMaxPx()).isEqualTo(512);
        assertThat(p.clips().perStory()).isEqualTo(3);
        assertThat(p.clips().maxSlice()).isEqualTo(Duration.ofSeconds(52));
        assertThat(p.madeForKids()).isFalse();
    }
}

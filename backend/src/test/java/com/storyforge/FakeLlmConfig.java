package com.storyforge;

import com.storyforge.llm.FakeLlmEngine;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class FakeLlmConfig {

    @Bean
    FakeLlmEngine fakeLlmEngine() {
        return new FakeLlmEngine();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "storyforge.animation.engine",
            havingValue = "fake")
    com.storyforge.animation.FakeAnimator fakeAnimator() {
        return new com.storyforge.animation.FakeAnimator();
    }

    @Bean
    com.storyforge.voice.FakeSpeech fakeSpeech() {
        return new com.storyforge.voice.FakeSpeech();
    }

    @Bean
    com.storyforge.clips.FakeVideoFetcher fakeVideoFetcher() {
        return new com.storyforge.clips.FakeVideoFetcher();
    }

    /** Only used when a test sets storyforge.images.engine to something other than "none". */
    @Bean
    com.storyforge.image.FakeImageEngine fakeImageEngine() {
        return new com.storyforge.image.FakeImageEngine();
    }
}

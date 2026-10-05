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
    com.storyforge.voice.FakeSpeech fakeSpeech() {
        return new com.storyforge.voice.FakeSpeech();
    }

    /** Only used when a test sets storyforge.images.engine to something other than "none". */
    @Bean
    com.storyforge.image.FakeImageEngine fakeImageEngine() {
        return new com.storyforge.image.FakeImageEngine();
    }
}

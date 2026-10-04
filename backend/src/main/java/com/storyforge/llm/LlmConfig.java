package com.storyforge.llm;

import com.storyforge.config.StoryforgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "storyforge.llm.engine", havingValue = "claude-code", matchIfMissing = true)
    LlmEngine claudeCodeEngine(StoryforgeProperties props) {
        return new ClaudeCodeEngine(props.llm());
    }
}

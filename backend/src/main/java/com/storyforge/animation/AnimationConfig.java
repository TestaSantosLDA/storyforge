package com.storyforge.animation;

import com.storyforge.config.StoryforgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AnimationConfig {

    @Bean
    @ConditionalOnProperty(name = "storyforge.animation.engine", havingValue = "storybook", matchIfMissing = true)
    AnimationEngine storybookAnimator(StoryforgeProperties props) {
        return new StorybookAnimator(props);
    }
}

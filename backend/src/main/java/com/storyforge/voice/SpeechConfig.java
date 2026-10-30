package com.storyforge.voice;

import com.storyforge.config.StoryforgeProperties;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class SpeechConfig {

    @Bean
    @ConditionalOnProperty(name = "storyforge.voices.engine", havingValue = "kokoro", matchIfMissing = true)
    SidecarSpeech sidecarSpeech(StoryforgeProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(props.images().timeout());
        var longFactory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        longFactory.setReadTimeout(props.clips().transcribeTimeout());
        return new SidecarSpeech(
                RestClient.builder().baseUrl(props.images().sidecarUrl()).requestFactory(factory).build(),
                RestClient.builder().baseUrl(props.images().sidecarUrl()).requestFactory(longFactory).build());
    }
}

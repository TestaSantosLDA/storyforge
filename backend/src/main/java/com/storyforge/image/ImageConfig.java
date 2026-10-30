package com.storyforge.image;

import com.storyforge.config.StoryforgeProperties;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class ImageConfig {

    @Bean
    @ConditionalOnProperty(name = "storyforge.images.engine", havingValue = "sidecar", matchIfMissing = true)
    ImageEngine sidecarImageEngine(StoryforgeProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(props.images().timeout());
        return new SidecarImageEngine(RestClient.builder().baseUrl(props.images().sidecarUrl())
                .requestFactory(factory).build());
    }
}

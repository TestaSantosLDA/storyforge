package com.storyforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StoryforgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(StoryforgeApplication.class, args);
	}

}

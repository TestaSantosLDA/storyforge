package com.storyforge;

import org.springframework.boot.SpringApplication;

public class TestStoryforgeApplication {

	public static void main(String[] args) {
		SpringApplication.from(StoryforgeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

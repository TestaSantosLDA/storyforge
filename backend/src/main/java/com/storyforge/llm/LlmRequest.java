package com.storyforge.llm;

import java.nio.file.Path;
import java.util.List;

/**
 * One bounded AI step: a system prompt, a user prompt and the JSON schema the answer must follow.
 * {@code promptVersion} is the template file it came from, for the attempt log. {@code images} are local files
 * Claude should look at (vision QA); the prompt names them by path.
 */
public record LlmRequest(String promptVersion, String systemPrompt, String userPrompt, String jsonSchema,
        List<Path> images) {

    public LlmRequest(String promptVersion, String systemPrompt, String userPrompt, String jsonSchema) {
        this(promptVersion, systemPrompt, userPrompt, jsonSchema, List.of());
    }
}

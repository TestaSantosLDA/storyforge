package com.storyforge.llm;

/**
 * One bounded AI step: a system prompt, a user prompt and the JSON schema the answer must follow.
 * {@code promptVersion} is the template file it came from, for the attempt log.
 */
public record LlmRequest(String promptVersion, String systemPrompt, String userPrompt, String jsonSchema) {
}

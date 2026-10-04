package com.storyforge.llm;

/**
 * Claude behind an interface (CLAUDE.md rule 3). MVP: {@link ClaudeCodeEngine}, which runs the locally installed
 * Claude Code CLI on the user's plan; later: the Claude API, selected by {@code storyforge.llm.engine}.
 */
public interface LlmEngine {

    /**
     * @throws LlmUnavailableException outage, usage limit or timeout: back off and retry, never a strike (rule 6)
     * @throws LlmBadOutputException the answer did not match the schema: a format QA failure
     */
    LlmResult complete(LlmRequest request);

    String name();
}

package com.storyforge.story;

/**
 * Starts the stage that owns a working status. The orchestrator registers one as stages are built; until then the
 * restart sweep only logs, and a story waits in its status until that stage exists.
 */
public interface StageDispatcher {

    void dispatch(long storyId, StoryStatus status);
}

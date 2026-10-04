package com.storyforge.story;

/** Outcome of Run (docs/pipeline-a/stage-0 scenarios 1, 7, 10). */
public sealed interface RunResult {

    record Started(long storyId) implements RunResult {
    }

    record NothingToRun() implements RunResult {
    }

    record AtCapacity(long inFlight, int cap) implements RunResult {
    }
}

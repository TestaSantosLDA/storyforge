package com.storyforge.story;

/** Why a story was archived (docs/pipeline-a/stage-1 "Retry budgets", Stage 4/5 Kill). */
public enum ArchiveReason {
    STORY_QA_RETRIES_EXHAUSTED,
    STORY_REJECTIONS_EXHAUSTED,
    SCRIPT_QA_RETRIES_EXHAUSTED,
    SCRIPT_REJECTIONS_EXHAUSTED,
    KILLED
}

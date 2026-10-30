package com.storyforge.stage1;

import com.storyforge.story.StoryEnteredStatus;
import com.storyforge.story.StoryStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** When a story is archived, its unreviewed drafts are abandoned in the same transaction. */
@Component
class DraftJanitor {

    private final Drafts drafts;

    DraftJanitor(Drafts drafts) {
        this.drafts = drafts;
    }

    @EventListener
    void onStatus(StoryEnteredStatus e) {
        if (e.status() == StoryStatus.ARCHIVED) {
            drafts.abandonPending(e.storyId());
        }
    }
}

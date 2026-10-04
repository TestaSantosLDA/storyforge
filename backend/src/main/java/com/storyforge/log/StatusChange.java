package com.storyforge.log;

import com.storyforge.story.StoryStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** One logged status change: from, to, who or what triggered it, and why (CLAUDE.md rule 8). */
@Entity
public class StatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long storyId;
    private StoryStatus fromStatus;
    private StoryStatus toStatus;
    private String triggeredBy;
    private String reason;
    private Instant changedAt;

    protected StatusChange() {
    }

    public StatusChange(Long storyId, StoryStatus from, StoryStatus to, String triggeredBy, String reason) {
        this.storyId = storyId;
        this.fromStatus = from;
        this.toStatus = to;
        this.triggeredBy = triggeredBy;
        this.reason = reason;
        this.changedAt = Instant.now();
    }

    public Long getStoryId() {
        return storyId;
    }

    public StoryStatus getFromStatus() {
        return fromStatus;
    }

    public StoryStatus getToStatus() {
        return toStatus;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}

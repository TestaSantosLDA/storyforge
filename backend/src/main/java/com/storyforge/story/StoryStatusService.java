package com.storyforge.story;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.log.StatusChange;
import com.storyforge.log.StatusChangeRepository;
import com.storyforge.topic.Topic;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code story.status} (CLAUDE.md rule 1). Every change locks the row, checks the caller's
 * expected current status and {@link StoryTransitions}, and is logged with who triggered it and why.
 * {@code triggeredBy} is "user:&lt;name&gt;" or "system:&lt;component&gt;".
 */
@Service
public class StoryStatusService {

    /** Targets with their own method, because they carry extra state (resume target, reason, queue position). */
    private static final Set<StoryStatus> SPECIAL_TARGETS =
            EnumSet.of(StoryStatus.QUEUED, StoryStatus.NEEDS_ATTENTION, StoryStatus.ARCHIVED);

    private final StoryRepository stories;
    private final StatusChangeRepository changes;
    private final QueueLock queueLock;
    private final StoryforgeProperties props;

    StoryStatusService(StoryRepository stories, StatusChangeRepository changes, QueueLock queueLock,
            StoryforgeProperties props) {
        this.stories = stories;
        this.changes = changes;
        this.queueLock = queueLock;
        this.props = props;
    }

    /** Adds a story at the bottom of the queue as {@code queued}. Validation of fields belongs to Stage 0. */
    @Transactional
    public Story enqueue(Topic topic, String concept, String moral, int targetLengthMin, String notes,
            String triggeredBy) {
        queueLock.acquire();
        Story story = stories.save(new Story(topic, concept, moral, targetLengthMin, notes,
                stories.maxQueuePosition() + 1));
        log(story, null, StoryStatus.QUEUED, triggeredBy, "added to queue");
        return story;
    }

    /**
     * Run: starts the first story in the queue if the in-flight count is under {@code max_concurrent_stories}.
     * Cap check, selection and status change happen in one transaction under the queue lock, so a double click
     * or a concurrent reorder can never start a story twice or exceed the cap (Stage 0 scenarios 8–10).
     */
    @Transactional
    public RunResult runNext(String triggeredBy) {
        queueLock.acquire();
        int cap = props.pipeline().maxConcurrentStories();
        long inFlight = stories.countByStatusIn(inFlightStatuses());
        if (inFlight >= cap) {
            return new RunResult.AtCapacity(inFlight, cap);
        }
        var top = stories.findQueuedForUpdate(Limit.of(1));
        if (top.isEmpty()) {
            return new RunResult.NothingToRun();
        }
        Story story = top.getFirst();
        story.setQueuePosition(null);
        story.setTopicSnapshot(story.getTopic().getDescription());
        move(story, StoryStatus.STORY_IN_PROGRESS, triggeredBy, "run: first in queue");
        return new RunResult.Started(story.getId());
    }

    /** A normal pipeline move, e.g. {@code story_in_progress → awaiting_story_approval}. */
    @Transactional
    public Story transition(long storyId, StoryStatus expectedFrom, StoryStatus to, String triggeredBy,
            String reason) {
        if (SPECIAL_TARGETS.contains(to)) {
            throw new IllegalArgumentException("use flag/archive/restore for " + to.dbValue());
        }
        Story story = lockExpecting(storyId, expectedFrom);
        if (expectedFrom == StoryStatus.NEEDS_ATTENTION) {
            throw new IllegalArgumentException("use resume/resumeTo to leave needs_attention");
        }
        move(story, to, triggeredBy, reason);
        return story;
    }

    /**
     * Stops the story and flags it for a human (CLAUDE.md rule 5). The current status is kept as the resume
     * target, so Resume knows where to go back to without hidden state.
     */
    @Transactional
    public Story flag(long storyId, StoryStatus expectedFrom, String reason, String triggeredBy) {
        Story story = lockExpecting(storyId, expectedFrom);
        StoryStatus resumeTo = story.getStatus();
        if (resumeTo.kind() != StoryStatus.Kind.WORKING) {
            throw new StatusConflictException("only a working status can be flagged, not " + resumeTo.dbValue());
        }
        move(story, StoryStatus.NEEDS_ATTENTION, triggeredBy, reason);
        story.setResumeStatus(resumeTo);
        story.setAttentionReason(reason);
        return story;
    }

    /** Resume: back to the step that failed. */
    @Transactional
    public Story resume(long storyId, String triggeredBy, String reason) {
        Story story = lockExpecting(storyId, StoryStatus.NEEDS_ATTENTION);
        return leaveAttention(story, story.getResumeStatus(), triggeredBy, reason);
    }

    /** Leaves needs_attention for a different working step, e.g. "send back to Gate B" from Stage 2. */
    @Transactional
    public Story resumeTo(long storyId, StoryStatus target, String triggeredBy, String reason) {
        Story story = lockExpecting(storyId, StoryStatus.NEEDS_ATTENTION);
        return leaveAttention(story, target, triggeredBy, reason);
    }

    /** Budget exhausted or Kill. */
    @Transactional
    public Story archive(long storyId, StoryStatus expectedFrom, ArchiveReason why, String triggeredBy,
            String reason) {
        Story story = lockExpecting(storyId, expectedFrom);
        move(story, StoryStatus.ARCHIVED, triggeredBy, why.name().toLowerCase() + (reason == null ? "" : ": " + reason));
        story.setResumeStatus(null);
        story.setArchiveReason(why);
        return story;
    }

    /** Restore: bottom of the queue, all counters reset, history kept (Stage 0 scenario 11, Stage 1 scenario 14). */
    @Transactional
    public Story restore(long storyId, String triggeredBy) {
        queueLock.acquire();
        Story story = lockExpecting(storyId, StoryStatus.ARCHIVED);
        story.setQueuePosition(stories.maxQueuePosition() + 1);
        story.setArchiveReason(null);
        story.setAttentionReason(null);
        story.setTopicSnapshot(null);
        story.resetCounters();
        move(story, StoryStatus.QUEUED, triggeredBy, "restored");
        return story;
    }

    public static Set<StoryStatus> inFlightStatuses() {
        EnumSet<StoryStatus> out = EnumSet.noneOf(StoryStatus.class);
        for (StoryStatus s : StoryStatus.values()) {
            if (s.inFlight()) {
                out.add(s);
            }
        }
        return out;
    }

    private Story leaveAttention(Story story, StoryStatus target, String triggeredBy, String reason) {
        if (target == null || target.kind() != StoryStatus.Kind.WORKING) {
            throw new StatusConflictException("needs_attention can only resume to a working status, not " + target);
        }
        move(story, target, triggeredBy, reason);
        story.setResumeStatus(null);
        story.setAttentionReason(null);
        return story;
    }

    private Story lockExpecting(long storyId, StoryStatus expectedFrom) {
        Story story = stories.findByIdForUpdate(storyId)
                .orElseThrow(() -> new StatusConflictException("no story " + storyId));
        if (story.getStatus() != expectedFrom) {
            throw new StatusConflictException("story " + storyId + " is " + story.getStatus().dbValue()
                    + ", expected " + expectedFrom.dbValue());
        }
        return story;
    }

    private void move(Story story, StoryStatus to, String triggeredBy, String reason) {
        StoryStatus from = story.getStatus();
        if (!StoryTransitions.isAllowed(from, to)) {
            throw new StatusConflictException("not allowed: " + from.dbValue() + " → " + to.dbValue());
        }
        story.setStatus(to);
        // Keep the leaving side's state consistent before the row is flushed (DB checks enforce it too).
        if (from == StoryStatus.NEEDS_ATTENTION) {
            story.setResumeStatus(null);
        }
        log(story, from, to, triggeredBy, reason);
    }

    private void log(Story story, StoryStatus from, StoryStatus to, String triggeredBy, String reason) {
        changes.save(new StatusChange(story.getId(), from, to, triggeredBy, reason));
    }
}

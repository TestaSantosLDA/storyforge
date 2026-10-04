package com.storyforge.story;

import com.storyforge.log.ChangeLog;
import com.storyforge.log.StatusChangeRepository;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import com.storyforge.validation.InvalidInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 0 story actions: add, edit, delete, reorder, Run, restore, resume. Edit, delete and reorder are allowed only
 * while a story is {@code queued}; the backend refuses otherwise, whatever the page shows (scenario 4).
 */
@Service
public class StoryQueueService {

    private final StoryRepository stories;
    private final TopicRepository topics;
    private final StoryStatusService status;
    private final StatusChangeRepository statusChanges;
    private final QueueLock queueLock;
    private final ChangeLog changeLog;

    StoryQueueService(StoryRepository stories, TopicRepository topics, StoryStatusService status,
            StatusChangeRepository statusChanges, QueueLock queueLock, ChangeLog changeLog) {
        this.stories = stories;
        this.topics = topics;
        this.status = status;
        this.statusChanges = statusChanges;
        this.queueLock = queueLock;
        this.changeLog = changeLog;
    }

    @Transactional(readOnly = true)
    public List<Story> queue() {
        return stories.findQueuedInOrder();
    }

    @Transactional
    public Story add(StoryForm input, String by) {
        StoryForm f = input.validated();
        Topic topic = topics.findById(f.topicId())
                .orElseThrow(() -> InvalidInputException.of("topicId", "That topic no longer exists."));
        Story story = status.enqueue(topic, f.concept(), f.moral(), f.targetLengthMin(), f.notes(), by);
        changeLog.record("story", story.getId(), "create", null, story.details(), by);
        return story;
    }

    @Transactional
    public Story edit(long storyId, StoryForm input, String by) {
        StoryForm f = input.validated();
        Story story = lockQueued(storyId, "edit");
        if (!story.getTopic().getId().equals(f.topicId())) {
            throw InvalidInputException.of("topicId", "A story can't move to another topic.");
        }
        Map<String, Object> before = story.details();
        story.updateDetails(f.concept(), f.moral(), f.targetLengthMin(), f.notes());
        changeLog.record("story", storyId, "edit", before, story.details(), by);
        return story;
    }

    @Transactional
    public void delete(long storyId, String by) {
        queueLock.acquire();
        Story story = lockQueued(storyId, "delete");
        Map<String, Object> before = story.details();
        statusChanges.deleteByStoryId(storyId);
        stories.delete(story);
        stories.flush();
        renumber(stories.findQueuedInOrder());
        changeLog.record("story", storyId, "delete", before, null, by);
    }

    /** Moves a queued story to {@code newPosition} (1 = top) and renumbers the queue without gaps. */
    @Transactional
    public void reorder(long storyId, int newPosition, String by) {
        queueLock.acquire();
        Story story = lockQueued(storyId, "reorder");
        List<Story> queue = new ArrayList<>(stories.findQueuedInOrder());
        int from = queue.indexOf(story);
        int to = Math.clamp(newPosition - 1, 0, queue.size() - 1);
        queue.remove(from);
        queue.add(to, story);
        Integer oldPosition = story.getQueuePosition();
        renumber(queue);
        changeLog.record("story", storyId, "reorder", Map.of("queue_position", oldPosition),
                Map.of("queue_position", story.getQueuePosition()), by);
    }

    @Transactional
    public RunResult run(String by) {
        RunResult result = status.runNext(by);
        if (result instanceof RunResult.Started(long id)) {
            changeLog.record("story", id, "run", null, Map.of("status", StoryStatus.STORY_IN_PROGRESS.dbValue()), by);
        }
        return result;
    }

    @Transactional
    public Story restore(long storyId, String by) {
        Story story = status.restore(storyId, by);
        changeLog.record("story", storyId, "restore", Map.of("status", StoryStatus.ARCHIVED.dbValue()),
                story.details(), by);
        return story;
    }

    @Transactional
    public Story resume(long storyId, String by) {
        return status.resume(storyId, by, "resumed from the management page");
    }

    private Story lockQueued(long storyId, String action) {
        Story story = stories.findByIdForUpdate(storyId)
                .orElseThrow(() -> new StatusConflictException("no story " + storyId));
        if (story.getStatus() != StoryStatus.QUEUED) {
            throw new StatusConflictException("can't " + action + " a story that has started ("
                    + story.getStatus().dbValue() + ")");
        }
        return story;
    }

    private void renumber(List<Story> inOrder) {
        for (int i = 0; i < inOrder.size(); i++) {
            inOrder.get(i).setQueuePosition(i + 1);
        }
    }
}

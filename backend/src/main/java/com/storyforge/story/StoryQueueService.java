package com.storyforge.story;

import com.storyforge.log.ChangeLog;
import com.storyforge.log.StatusChangeRepository;
import com.storyforge.profile.LookNotesCheck;
import com.storyforge.series.SourceExcerpt;
import com.storyforge.series.SourceTexts;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import com.storyforge.validation.InvalidInputException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final LookNotesCheck names;
    private final SourceTexts sources;

    /** A previous episode at or past these is far enough along to run the next one without asking (C5). */
    private static final Set<StoryStatus> PREVIOUS_DONE = EnumSet.of(StoryStatus.FINAL_APPROVED,
            StoryStatus.PREPARING_PUBLISH, StoryStatus.READY_TO_UPLOAD, StoryStatus.PUBLISHED);

    StoryQueueService(StoryRepository stories, TopicRepository topics, StoryStatusService status,
            StatusChangeRepository statusChanges, QueueLock queueLock, ChangeLog changeLog, LookNotesCheck names,
            SourceTexts sources) {
        this.names = names;
        this.sources = sources;
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
        Episode episode = checkSeriesFields(f, topic, null);
        // A topic with a video profile makes Motion Pictures (Pipeline D); without one, fables.
        StoryFormat format = topic.getProfileId() == null ? StoryFormat.FABLE : StoryFormat.MOTION_PICTURE;
        Story story = status.enqueue(topic, format, f.concept(), f.moral(), f.targetLengthMin(), f.notes(), by);
        story.setLookNotes(f.lookNotes());
        story.setEpisode(episode.season(), episode.number(), episode.excerptJson(), episode.recap());
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
        Episode episode = checkSeriesFields(f, story.getTopic(), story.getId());
        Map<String, Object> before = story.details();
        story.updateDetails(f.concept(), f.moral(), f.targetLengthMin(), f.notes());
        story.setLookNotes(f.lookNotes());
        story.setEpisode(episode.season(), episode.number(), episode.excerptJson(), episode.recap());
        changeLog.record("story", storyId, "edit", before, story.details(), by);
        return story;
    }

    private record Episode(Integer season, Integer number, String excerptJson, boolean recap) {
        static final Episode NONE = new Episode(null, null, null, false);
    }

    /**
     * Look notes naming a studio, franchise or existing character are refused (Pipeline D), unless the name is on
     * the topic's public-domain allowlist (C9). For a series topic (C5): an episode number, unique per topic and
     * season; and, when the topic has a source text, a source excerpt within it (scenario 2). Every problem at once.
     */
    private Episode checkSeriesFields(StoryForm f, Topic topic, Long selfId) {
        Map<String, String> errors = new LinkedHashMap<>();
        names.findBanned(f.lookNotes(), LookNotesCheck.allowlist(topic.getNameAllowlist()))
                .ifPresent(n -> errors.put("lookNotes", LookNotesCheck.refusal(n)));
        Episode episode = Episode.NONE;
        if (topic.isSeries()) {
            int season = f.season() == null ? 1 : f.season();
            if (season < 1) {
                errors.put("season", "Seasons start at 1.");
            }
            Integer number = f.episodeNumber();
            if (number == null) {
                errors.put("episodeNumber", "Give the episode number.");
            } else if (number < 0) {
                errors.put("episodeNumber", "Episode numbers start at 0.");
            } else {
                stories.findByTopicIdAndSeasonAndEpisodeNumber(topic.getId(), season, number)
                        .filter(other -> !other.getId().equals(selfId))
                        .ifPresent(other -> errors.put("episodeNumber", String.format(
                                "Season %d already has an episode %d: \"%s\".", season, number, other.getConcept())));
            }
            String excerptJson = null;
            if (topic.getSourceId() != null) {
                Optional<SourceTexts.Source> source = sources.find(topic.getSourceId());
                if (source.isEmpty()) {
                    errors.put("sourceExcerpt", "The topic's source text sources/" + topic.getSourceId()
                            + " is missing.");
                } else {
                    try {
                        excerptJson = SourceExcerpt.parse(f.sourceExcerpt(), source.get()).toJson();
                    } catch (IllegalArgumentException e) {
                        errors.put("sourceExcerpt", e.getMessage());
                    }
                }
            }
            episode = new Episode(season, number, excerptJson, f.recapEnabled() != null && f.recapEnabled());
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return episode;
    }

    /**
     * Why Run should ask first, if it should (C5): the story at the top of the queue is a series episode and the
     * episode before it is missing or not yet final-approved. Not a block; the page asks for a confirmation.
     */
    @Transactional(readOnly = true)
    public Optional<String> runWarning() {
        List<Story> queued = stories.findQueuedInOrder();
        if (queued.isEmpty()) {
            return Optional.empty();
        }
        Story top = queued.getFirst();
        Integer number = top.getEpisodeNumber();
        if (number == null || number == 0) {
            return Optional.empty();
        }
        Optional<Story> previous = stories.findByTopicIdAndSeasonAndEpisodeNumber(top.getTopic().getId(),
                top.getSeason(), number - 1);
        if (previous.isEmpty()) {
            return Optional.of(String.format("%s is next, but there is no episode %d in season %d yet.",
                    top.getEpisodeLabel(), number - 1, top.getSeason()));
        }
        if (!PREVIOUS_DONE.contains(previous.get().getStatus())) {
            return Optional.of(String.format("%s is next, but episode %d is still %s, not final-approved.",
                    top.getEpisodeLabel(), number - 1, previous.get().getStatus().dbValue().replace('_', ' ')));
        }
        return Optional.empty();
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

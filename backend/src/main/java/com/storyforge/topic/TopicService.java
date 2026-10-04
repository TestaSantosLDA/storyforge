package com.storyforge.topic;

import com.storyforge.character.CharacterStore;
import com.storyforge.log.ChangeLog;
import com.storyforge.story.Story;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.validation.InvalidInputException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stage 0 topic actions (docs/pipeline-a/stage-0 "Actions and rules"). */
@Service
public class TopicService {

    private final TopicRepository topics;
    private final StoryRepository stories;
    private final StoryQueueService queue;
    private final CharacterStore characters;
    private final ChangeLog changeLog;

    TopicService(TopicRepository topics, StoryRepository stories, StoryQueueService queue, CharacterStore characters,
            ChangeLog changeLog) {
        this.topics = topics;
        this.stories = stories;
        this.queue = queue;
        this.characters = characters;
        this.changeLog = changeLog;
    }

    @Transactional(readOnly = true)
    public List<Topic> all() {
        return topics.findAllByOrderByNameAsc();
    }

    @Transactional
    public Topic create(String name, String description, String by) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "Give the topic a name.");
        } else if (topics.existsByNameIgnoreCase(name.strip())) {
            errors.put("name", "A topic with this name already exists.");
        }
        if (description == null || description.isBlank()) {
            errors.put("description", "Describe the universe: its setting and feel.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Topic topic = topics.save(new Topic(name.strip(), description.strip()));
        changeLog.record("topic", topic.getId(), "create", null, details(topic), by);
        return topic;
    }

    /** Applies to stories started after the edit; in-flight stories keep their snapshot (scenario 5). */
    @Transactional
    public Topic editDescription(long topicId, String description, String by) {
        if (description == null || description.isBlank()) {
            throw InvalidInputException.of("description", "Describe the universe: its setting and feel.");
        }
        Topic topic = topics.findById(topicId).orElseThrow();
        Map<String, Object> before = details(topic);
        topic.setDescription(description.strip());
        changeLog.record("topic", topicId, "edit", before, details(topic), by);
        return topic;
    }

    /** Allowed only if none of its stories ever started and it has no characters; removes its queued stories. */
    @Transactional
    public void delete(long topicId, String by) {
        Topic topic = topics.findById(topicId).orElseThrow();
        if (stories.anyStartedInTopic(topicId)) {
            throw new TopicInUseException("This topic has stories that have started, so it can't be deleted.");
        }
        if (!characters.byTopic(topicId).isEmpty()) {
            throw new TopicInUseException("This topic has characters, so it can't be deleted.");
        }
        for (Story s : stories.findByTopicId(topicId)) {
            queue.delete(s.getId(), by);
        }
        Map<String, Object> before = details(topic);
        topics.delete(topic);
        changeLog.record("topic", topicId, "delete", before, null, by);
    }

    private static Map<String, Object> details(Topic t) {
        return Map.of("name", t.getName(), "description", t.getDescription());
    }
}

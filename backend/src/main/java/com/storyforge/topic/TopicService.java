package com.storyforge.topic;

import com.storyforge.character.CharacterStore;
import com.storyforge.log.ChangeLog;
import com.storyforge.profile.LookNotesCheck;
import com.storyforge.profile.ProfileException;
import com.storyforge.profile.ProfileSnapshots;
import com.storyforge.series.ChannelRepository;
import com.storyforge.series.SourceTexts;
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
    private final ProfileSnapshots profiles;
    private final LookNotesCheck names;
    private final ChannelRepository channels;
    private final SourceTexts sources;

    TopicService(TopicRepository topics, StoryRepository stories, StoryQueueService queue, CharacterStore characters,
            ChangeLog changeLog, ProfileSnapshots profiles, LookNotesCheck names, ChannelRepository channels,
            SourceTexts sources) {
        this.channels = channels;
        this.sources = sources;
        this.profiles = profiles;
        this.names = names;
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
        // New topics start on the default (fables) channel; the topic page can move them (C2).
        Topic topic = topics.save(new Topic(name.strip(), description.strip(), channels.findDefault()));
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

    /**
     * Sets the topic's video profile, its overrides (YAML text, one "field: value" per line) and look notes
     * (Pipeline D "Video profiles"). A blank profile makes it a fable topic again. The merged profile is checked
     * now, and look notes naming a studio, franchise or existing character are refused (scenario 2c). Applies to
     * stories started afterwards; running stories keep their snapshot.
     */
    @Transactional
    public Topic setProfile(long topicId, String profileId, String overridesYaml, String lookNotes, String by) {
        Topic topic = topics.findById(topicId).orElseThrow();
        String id = profileId == null || profileId.isBlank() ? null : profileId.strip();
        String notes = lookNotes == null || lookNotes.isBlank() ? null : lookNotes.strip();
        Map<String, String> errors = new LinkedHashMap<>();
        names.findBanned(notes, LookNotesCheck.allowlist(topic.getNameAllowlist()))
                .ifPresent(n -> errors.put("lookNotes", LookNotesCheck.refusal(n)));
        Map<String, Object> overrides = Map.of();
        try {
            overrides = ProfileSnapshots.parseOverrides(overridesYaml);
            if (id == null && !overrides.isEmpty()) {
                errors.put("overrides", "Pick a profile before overriding its fields.");
            } else if (id != null) {
                profiles.effective(id, overrides);
            }
        } catch (ProfileException e) {
            errors.put(e.fieldErrors().containsKey("profile") ? "profileId" : "overrides", e.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Map<String, Object> before = details(topic);
        topic.setProfile(id, ProfileSnapshots.toJson(overrides), notes);
        changeLog.record("topic", topicId, "profile", before, details(topic), by);
        return topic;
    }

    /**
     * Sets the topic's channel and series fields (docs/series/lusiadas.md C2, C5, C9): whether its stories are
     * numbered episodes, the public-domain source text under sources/ (blank for none), and the names from that
     * source allowed in prompts, scripts and look notes (one per line). Applies to stories added or run afterwards.
     */
    @Transactional
    public Topic setSeries(long topicId, long channelId, boolean isSeries, String sourceId, String allowlistText,
            String by) {
        Topic topic = topics.findById(topicId).orElseThrow();
        Map<String, String> errors = new LinkedHashMap<>();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) {
            errors.put("channelId", "That channel no longer exists.");
        }
        String source = sourceId == null || sourceId.isBlank() ? null : sourceId.strip();
        if (source != null && sources.find(source).isEmpty()) {
            errors.put("sourceId", "There is no source text sources/" + source + "/source.yaml.");
        }
        if (source != null && !isSeries) {
            errors.put("sourceId", "A source text is for a series; tick \"Series\" or clear the source.");
        }
        List<String> allow = allowlistText == null ? List.of() : allowlistText.lines().map(String::strip)
                .filter(l -> !l.isEmpty()).distinct().toList();
        for (String n : allow) {
            names.findBanned(n).ifPresent(b -> errors.put("nameAllowlist", "\"" + n + "\" names " + b
                    + ", a studio, franchise or copyrighted character; only names from the public-domain source may "
                    + "be allowed."));
        }
        if (!allow.isEmpty() && source == null) {
            errors.put("nameAllowlist", "Allowed names come from the topic's public-domain source; pick one first.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Map<String, Object> before = details(topic);
        topic.setChannel(channel.get());
        topic.setSeries(isSeries, source, allow.isEmpty() ? null : LookNotesCheck.allowlistJson(allow));
        changeLog.record("topic", topicId, "series", before, details(topic), by);
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("profile_id", t.getProfileId());
        m.put("profile_overrides", t.getProfileOverrides());
        m.put("look_notes", t.getLookNotes());
        m.put("channel_id", t.getChannel() == null ? null : t.getChannel().getId());
        m.put("is_series", t.isSeries());
        m.put("source_id", t.getSourceId());
        m.put("name_allowlist", t.getNameAllowlist());
        return m;
    }
}

package com.storyforge.story;

import com.storyforge.validation.InvalidInputException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fields a person enters for a story (docs/pipeline-a/stage-0 "Story" and "Validation"), plus the story's own look
 * notes for a topic with a video profile (Pipeline D "Look notes") and, for a series topic, the episode fields
 * (docs/series/lusiadas.md C5). The episode fields are checked against the topic in {@link StoryQueueService}.
 */
public record StoryForm(Long topicId, String concept, String moral, Integer targetLengthMin, String notes,
        String lookNotes, Integer season, Integer episodeNumber, String sourceExcerpt, Boolean recapEnabled) {

    public static final int DEFAULT_LENGTH_MIN = 4;

    public StoryForm(Long topicId, String concept, String moral, Integer targetLengthMin, String notes) {
        this(topicId, concept, moral, targetLengthMin, notes, null);
    }

    public StoryForm(Long topicId, String concept, String moral, Integer targetLengthMin, String notes,
            String lookNotes) {
        this(topicId, concept, moral, targetLengthMin, notes, lookNotes, null, null, null, null);
    }

    public static StoryForm blank(Long topicId) {
        return new StoryForm(topicId, "", "", DEFAULT_LENGTH_MIN, "", "", 1, null, "", false);
    }

    public static StoryForm of(Story s) {
        var excerpt = com.storyforge.series.SourceExcerpt.fromJson(s.getSourceExcerpt());
        return new StoryForm(s.getTopic().getId(), s.getConcept(), s.getMoral(), s.getTargetLengthMin(), s.getNotes(),
                s.getLookNotes(), s.getSeason(), s.getEpisodeNumber(), excerpt == null ? "" : excerpt.asText(),
                s.isRecapEnabled());
    }

    /** Throws with every field problem at once. */
    public StoryForm validated() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (topicId == null) {
            errors.put("topicId", "Pick a topic.");
        }
        if (isBlank(concept)) {
            errors.put("concept", "Describe the story idea.");
        }
        if (isBlank(moral)) {
            errors.put("moral", "Give the moral.");
        }
        int length = targetLengthMin == null ? DEFAULT_LENGTH_MIN : targetLengthMin;
        if (length < 3 || length > 5) {
            errors.put("targetLengthMin", "Target length must be 3 to 5 minutes.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return new StoryForm(topicId, concept.strip(), moral.strip(), length, isBlank(notes) ? null : notes.strip(),
                isBlank(lookNotes) ? null : lookNotes.strip(), season, episodeNumber,
                isBlank(sourceExcerpt) ? null : sourceExcerpt.strip(), Boolean.TRUE.equals(recapEnabled));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

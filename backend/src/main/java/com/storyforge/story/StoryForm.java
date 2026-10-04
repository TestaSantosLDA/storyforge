package com.storyforge.story;

import com.storyforge.validation.InvalidInputException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fields a person enters for a story (docs/pipeline-a/stage-0 "Story" and "Validation"). */
public record StoryForm(Long topicId, String concept, String moral, Integer targetLengthMin, String notes) {

    public static final int DEFAULT_LENGTH_MIN = 4;

    public static StoryForm blank(Long topicId) {
        return new StoryForm(topicId, "", "", DEFAULT_LENGTH_MIN, "");
    }

    public static StoryForm of(Story s) {
        return new StoryForm(s.getTopic().getId(), s.getConcept(), s.getMoral(), s.getTargetLengthMin(), s.getNotes());
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
        return new StoryForm(topicId, concept.strip(), moral.strip(), length, isBlank(notes) ? null : notes.strip());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

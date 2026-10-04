package com.storyforge.character;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One character file (docs/pipeline-a/stage-0 "Character"), stored as {@code characters/<id>.yaml} in the repo.
 * Reference images are asset keys (keep-forever retention), not repo files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterFile(
        String id,
        String name,
        Long topicId,
        Kind kind,
        String personality,
        String visualDescription,
        List<String> referenceImages,
        Map<String, Object> voice,
        Long introducedInStory) {

    public enum Kind {
        @JsonProperty("recurring") RECURRING,
        @JsonProperty("one_off") ONE_OFF
    }
}

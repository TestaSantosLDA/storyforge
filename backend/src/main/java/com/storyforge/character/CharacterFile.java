package com.storyforge.character;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One character file (docs/pipeline-a/stage-0 "Character"), stored as {@code characters/<id>.yaml} in the repo.
 * Reference images are asset keys (keep-forever retention), not repo files.
 *
 * <p>A character drawn by kit code (docs/series/lusiadas.md C10) has {@code drawn_by: code}, names its
 * {@code kit_component} and poses, and has no reference images. A long-dead real person (C3) has
 * {@code historical: true} and the year they {@code died}; they may only be drawn by code.
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
        Long introducedInStory,
        DrawnBy drawnBy,
        String kitComponent,
        List<String> poses,
        Boolean historical,
        Integer died) {

    public enum Kind {
        @JsonProperty("recurring") RECURRING,
        @JsonProperty("one_off") ONE_OFF
    }

    public enum DrawnBy {
        @JsonProperty("flux") FLUX,
        @JsonProperty("code") CODE
    }

    /** A Flux-drawn character, as every character was before C10. */
    public CharacterFile(String id, String name, Long topicId, Kind kind, String personality, String visualDescription,
            List<String> referenceImages, Map<String, Object> voice, Long introducedInStory) {
        this(id, name, topicId, kind, personality, visualDescription, referenceImages, voice, introducedInStory, null,
                null, null, null, null);
    }

    /** {@code flux} unless the file says {@code code}. */
    public DrawnBy drawing() {
        return drawnBy == null ? DrawnBy.FLUX : drawnBy;
    }

    /** Not named isHistorical(): Jackson would read that as the {@code historical} property's getter. */
    public boolean historicalFigure() {
        return Boolean.TRUE.equals(historical);
    }

    /**
     * What makes the file invalid, empty if nothing: a code character needs its kit component and no reference
     * images; a historical figure needs the year they died, before {@code diedBefore}, and must be drawn by code
     * (C3: AI-generated likenesses of real people are never allowed).
     */
    public List<String> problems(int diedBefore) {
        List<String> p = new ArrayList<>();
        if (drawing() == DrawnBy.CODE) {
            if (kitComponent == null || kitComponent.isBlank()) {
                p.add(name + " is drawn by code but names no kit component.");
            }
            if (referenceImages != null && !referenceImages.isEmpty()) {
                p.add(name + " is drawn by code, so it has a pose sheet, not Flux reference images.");
            }
        }
        if (historicalFigure()) {
            if (died == null) {
                p.add(name + " is a historical figure but has no year of death.");
            } else if (died >= diedBefore) {
                p.add(name + " died in " + died + "; only people who died before " + diedBefore
                        + " may be drawn recognisably.");
            }
            if (drawing() != DrawnBy.CODE) {
                p.add(name + " is a real person, so they may only be drawn by kit code, never by an image model.");
            }
        }
        return p;
    }
}

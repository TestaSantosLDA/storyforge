package com.storyforge.stage1;

import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.Story;
import com.storyforge.story.StoryCounter;
import com.storyforge.story.StoryCounters;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.validation.InvalidInputException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gate A (story + cast) and Gate B (script): Approve, Regenerate with notes, Kill. */
@Service
public class Gates {

    public enum Gate { STORY, SCRIPT }

    public enum Outcome { REGENERATING, ARCHIVED }

    private final StoryStatusService status;
    private final StoryCounters counters;
    private final Drafts drafts;
    private final CharacterStore characters;
    private final StoryRepository stories;
    private final StoryforgeProperties.Voices voices;
    private final ReferenceSheets sheets;

    Gates(StoryStatusService status, StoryCounters counters, Drafts drafts, CharacterStore characters,
            StoryRepository stories, StoryforgeProperties props, ReferenceSheets sheets) {
        this.status = status;
        this.counters = counters;
        this.drafts = drafts;
        this.characters = characters;
        this.stories = stories;
        this.voices = props.voices();
        this.sheets = sheets;
    }

    /**
     * Gate A approve: a character file for every new character, voices assigned, cast locked (the approved draft),
     * then straight on to the script. Character files are written last, so a clash rolls the whole approval back.
     */
    @Transactional
    public void approveStory(long storyId, String by) {
        Drafts.Draft draft = pending(storyId, Drafts.Kind.STORY);
        Story story = stories.findWithTopic(storyId).orElseThrow();
        status.transition(storyId, StoryStatus.AWAITING_STORY_APPROVAL, StoryStatus.STORY_APPROVED, by, "approved");
        drafts.review(draft.id(), "approved", null);
        status.transition(storyId, StoryStatus.STORY_APPROVED, StoryStatus.SCRIPT_IN_PROGRESS,
                "system:stage1", "cast locked; writing the script");
        StoryContent content = Json.read(draft.content(), StoryContent.class);
        Map<String, ReferenceSheets.Sheet> sheetsById = draft.sheets() == null ? Map.of()
                : Json.MAPPER.readValue(draft.sheets(), Json.MAPPER.getTypeFactory()
                        .constructMapType(java.util.LinkedHashMap.class, String.class, ReferenceSheets.Sheet.class));
        List<CharacterFile> files = content.newCharacters().stream()
                .map(n -> {
                    ReferenceSheets.Sheet sheet = sheetsById.get(n.id());
                    // A sheet that failed QA after all draws can still be approved: the reviewer has seen it.
                    if (sheets.enabled() && sheet == null) {
                        throw InvalidInputException.of("cast", n.name() + " has no reference sheet yet.");
                    }
                    List<String> refs = sheet == null ? List.of() : sheets.keepForever(n.id(), sheet.views());
                    return new CharacterFile(n.id(), n.name(), story.getTopic().getId(),
                            "one_off".equals(n.kind()) ? CharacterFile.Kind.ONE_OFF : CharacterFile.Kind.RECURRING,
                            n.personality(), n.visualDescription(), refs,
                            Map.of("engine", voices.engine(), "id", n.proposedVoice()), storyId);
                })
                .toList();
        for (CharacterFile f : files) {
            try {
                characters.create(f);
            } catch (IllegalArgumentException e) {
                throw InvalidInputException.of("cast", e.getMessage()
                        + ". Regenerate the story with a note to rename this character.");
            }
        }
    }

    @Transactional
    public void approveScript(long storyId, String by) {
        Drafts.Draft draft = pending(storyId, Drafts.Kind.SCRIPT);
        status.transition(storyId, StoryStatus.AWAITING_SCRIPT_APPROVAL, StoryStatus.SCRIPT_APPROVED, by, "approved");
        drafts.review(draft.id(), "approved", null);
    }

    /** Regenerate with notes: counts a rejection; the limit archives the story. */
    @Transactional
    public Outcome regenerate(long storyId, Gate gate, String notes, String by) {
        if (notes == null || notes.isBlank()) {
            throw InvalidInputException.of("notes", "Say what to change.");
        }
        boolean story = gate == Gate.STORY;
        StoryStatus waiting = story ? StoryStatus.AWAITING_STORY_APPROVAL : StoryStatus.AWAITING_SCRIPT_APPROVAL;
        Drafts.Draft draft = pending(storyId, story ? Drafts.Kind.STORY : Drafts.Kind.SCRIPT);
        drafts.review(draft.id(), "rejected", notes.strip());
        StoryCounters.Count count = counters.increment(storyId,
                story ? StoryCounter.STORY_REJECTIONS : StoryCounter.SCRIPT_REJECTIONS);
        if (count.reachedLimit()) {
            status.archive(storyId, waiting,
                    story ? ArchiveReason.STORY_REJECTIONS_EXHAUSTED : ArchiveReason.SCRIPT_REJECTIONS_EXHAUSTED,
                    by, notes.strip());
            return Outcome.ARCHIVED;
        }
        status.transition(storyId, waiting, story ? StoryStatus.STORY_IN_PROGRESS : StoryStatus.SCRIPT_IN_PROGRESS,
                by, "regenerate: " + notes.strip());
        return Outcome.REGENERATING;
    }

    /** Kill at either gate. No character files are created at Gate A; approved characters stay after Gate B. */
    @Transactional
    public void kill(long storyId, Gate gate, String by) {
        StoryStatus waiting = gate == Gate.STORY ? StoryStatus.AWAITING_STORY_APPROVAL : StoryStatus.AWAITING_SCRIPT_APPROVAL;
        status.archive(storyId, waiting, ArchiveReason.KILLED, by, "killed at gate " + (gate == Gate.STORY ? "A" : "B"));
    }

    private Drafts.Draft pending(long storyId, Drafts.Kind kind) {
        return drafts.latest(storyId, kind).filter(d -> "pending".equals(d.reviewState()))
                .orElseThrow(() -> new com.storyforge.story.StatusConflictException("no " + kind.name().toLowerCase()
                        + " draft is waiting for review"));
    }
}

package com.storyforge.story;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Pipeline A story statuses (CLAUDE.md "Status values"). Each status has one {@link Kind}, which drives the
 * concurrency cap and the restart sweep (docs/foundations.md "Status classification").
 */
public enum StoryStatus {

    QUEUED(Kind.QUEUED),
    STORY_IN_PROGRESS(Kind.WORKING),
    AWAITING_STORY_APPROVAL(Kind.HUMAN),
    STORY_APPROVED(Kind.HANDOFF),
    SCRIPT_IN_PROGRESS(Kind.WORKING),
    AWAITING_SCRIPT_APPROVAL(Kind.HUMAN),
    SCRIPT_APPROVED(Kind.HANDOFF),
    AUDIO_IN_PROGRESS(Kind.WORKING),
    AUDIO_DONE(Kind.HANDOFF),
    VISUALS_IN_PROGRESS(Kind.WORKING),
    VISUALS_DONE(Kind.HANDOFF),
    ASSEMBLING(Kind.WORKING),
    AWAITING_FINAL_REVIEW(Kind.HUMAN),
    ANALYZING_REJECTION(Kind.WORKING),
    FINAL_APPROVED(Kind.HANDOFF),
    PREPARING_PUBLISH(Kind.WORKING),
    READY_TO_UPLOAD(Kind.HUMAN),
    PUBLISHED(Kind.TERMINAL),
    NEEDS_ATTENTION(Kind.HUMAN),
    ARCHIVED(Kind.TERMINAL);

    public enum Kind {
        /** Waiting in the queue for Run. */
        QUEUED,
        /** A stage is doing work. After a crash the stage is re-run; caching makes that cheap. */
        WORKING,
        /** Waiting on a person at a gate, a checklist, or a flag. */
        HUMAN,
        /** Written by a finished stage, not yet picked up by the next one. After a crash it is advanced. */
        HANDOFF,
        /** Nothing more happens without a human action (Restore) or ever (published). */
        TERMINAL
    }

    private final Kind kind;

    StoryStatus(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The database and docs spelling, e.g. {@code story_in_progress}. */
    public String dbValue() {
        return name().toLowerCase();
    }

    public static StoryStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }

    /** Counts toward {@code max_concurrent_stories}: everything that has left the queue and is not finished. */
    public boolean inFlight() {
        return kind != Kind.QUEUED && kind != Kind.TERMINAL;
    }

    /** The working status a handoff status hands to. Empty for every other kind. */
    public Optional<StoryStatus> handoffTarget() {
        return Optional.ofNullable(switch (this) {
            case STORY_APPROVED -> SCRIPT_IN_PROGRESS;
            case SCRIPT_APPROVED -> AUDIO_IN_PROGRESS;
            case AUDIO_DONE -> VISUALS_IN_PROGRESS;
            case VISUALS_DONE -> ASSEMBLING;
            case FINAL_APPROVED -> PREPARING_PUBLISH;
            default -> null;
        });
    }

    public static Set<StoryStatus> ofKind(Kind kind) {
        EnumSet<StoryStatus> out = EnumSet.noneOf(StoryStatus.class);
        Arrays.stream(values()).filter(s -> s.kind == kind).forEach(out::add);
        return out;
    }
}

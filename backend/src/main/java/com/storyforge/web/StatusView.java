package com.storyforge.web;

import com.storyforge.story.StoryStatus;
import java.util.EnumSet;
import java.util.Set;

/** The page views per status (docs/pipeline-a/stage-0 "Page views"). Queue and Topics have their own pages. */
public enum StatusView {

    IN_PROGRESS("in-progress", "In progress", "Stories moving through the pipeline.",
            union(StoryStatus.ofKind(StoryStatus.Kind.WORKING), StoryStatus.ofKind(StoryStatus.Kind.HANDOFF))),
    AWAITING("awaiting", "Awaiting approval", "Stories waiting at a human gate.",
            EnumSet.of(StoryStatus.AWAITING_STORY_APPROVAL, StoryStatus.AWAITING_SCRIPT_APPROVAL,
                    StoryStatus.AWAITING_FINAL_REVIEW, StoryStatus.READY_TO_UPLOAD)),
    ATTENTION("attention", "Needs attention", "Stories stopped by a failure. Resume goes back to the step that failed.",
            EnumSet.of(StoryStatus.NEEDS_ATTENTION)),
    PUBLISHED("published", "Published", "Finished stories.", EnumSet.of(StoryStatus.PUBLISHED)),
    ARCHIVE("archive", "Archive", "Archived stories. Restore puts one back at the bottom of the queue.",
            EnumSet.of(StoryStatus.ARCHIVED));

    private final String slug;
    private final String title;
    private final String blurb;
    private final Set<StoryStatus> statuses;

    StatusView(String slug, String title, String blurb, Set<StoryStatus> statuses) {
        this.slug = slug;
        this.title = title;
        this.blurb = blurb;
        this.statuses = statuses;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public String blurb() {
        return blurb;
    }

    public Set<StoryStatus> statuses() {
        return statuses;
    }

    public static StatusView fromSlug(String slug) {
        for (StatusView v : values()) {
            if (v.slug.equals(slug)) {
                return v;
            }
        }
        throw new IllegalArgumentException("no view " + slug);
    }

    private static Set<StoryStatus> union(Set<StoryStatus> a, Set<StoryStatus> b) {
        EnumSet<StoryStatus> out = EnumSet.copyOf(a);
        out.addAll(b);
        return out;
    }
}

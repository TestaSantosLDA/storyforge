package com.storyforge.stage5;

import com.storyforge.notes.StoryNotes;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.validation.InvalidInputException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Step 5B: the publish screen's actions (edit, regenerate, mark as published, copyright claims, kill). */
@Service
public class Publishing {

    /** youtube.com/watch?v=ID, youtu.be/ID or youtube.com/shorts/ID with an 11-character id. */
    static final Pattern YOUTUBE = Pattern.compile(
            "^https?://(www\\.|m\\.)?(youtube\\.com/(watch\\?v=|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})([&?#].*)?$");

    public record Package(int version, PublishStep.Metadata metadata, List<PublishStep.Thumbnail> thumbnails,
            String qa, boolean passed) {
    }

    private final StoryStatusService status;
    private final StoryNotes notes;
    private final JdbcClient jdbc;

    Publishing(StoryStatusService status, StoryNotes notes, JdbcClient jdbc) {
        this.status = status;
        this.notes = notes;
        this.jdbc = jdbc;
    }

    public Optional<Package> latest(long storyId) {
        return jdbc.sql("select * from publish_package where story_id = :s order by version desc limit 1")
                .param("s", storyId).query((rs, n) -> new Package(rs.getInt("version"),
                        ShotPlanner.JSON.readValue(rs.getString("metadata"), PublishStep.Metadata.class),
                        List.of(ShotPlanner.JSON.readValue(rs.getString("thumbnails"), PublishStep.Thumbnail[].class)),
                        rs.getString("qa"), rs.getBoolean("passed"))).optional();
    }

    public Optional<String> publishedUrl(long storyId) {
        return jdbc.sql("select youtube_url from publication where story_id = :s").param("s", storyId)
                .query(String.class).optional();
    }

    /** Edits on the page are saved as a new package version (every edit is logged). */
    @Transactional
    public void edit(long storyId, String title, String description, String tags) {
        Package p = latest(storyId).orElseThrow();
        if (title == null || title.isBlank() || title.length() > 100) {
            throw InvalidInputException.of("title", "The title must be 1–100 characters.");
        }
        List<String> tagList = tags == null ? List.of() : java.util.Arrays.stream(tags.split(","))
                .map(String::strip).filter(t -> !t.isEmpty()).toList();
        var m = new PublishStep.Metadata(title.strip(), description == null ? "" : description.strip(), tagList,
                p.metadata().chapters(), p.metadata().playlist());
        jdbc.sql("""
                insert into publish_package (story_id, version, metadata, thumbnails, qa, passed, notes)
                select story_id, version + 1, cast(:m as jsonb), thumbnails, qa, passed, 'edited on the page'
                from publish_package where story_id = :s and version = :v
                """).param("m", ShotPlanner.JSON.writeValueAsString(m)).param("s", storyId)
                .param("v", p.version()).update();
    }

    /** Regenerate with notes: the metadata step runs again with them. */
    @Transactional
    public void regenerate(long storyId, String reviewerNotes, String by) {
        if (reviewerNotes == null || reviewerNotes.isBlank()) {
            throw InvalidInputException.of("notes", "Say what to change.");
        }
        jdbc.sql("update publish_package set notes = :n where story_id = :s and version = "
                        + "(select max(version) from publish_package where story_id = :s)")
                .param("n", reviewerNotes.strip()).param("s", storyId).update();
        status.transition(storyId, StoryStatus.READY_TO_UPLOAD, StoryStatus.PREPARING_PUBLISH, by,
                "regenerate metadata: " + reviewerNotes.strip());
    }

    @Transactional
    public void markPublished(long storyId, String url, String by) {
        if (url == null || !YOUTUBE.matcher(url.strip()).matches()) {
            throw InvalidInputException.of("url", "That isn't a YouTube video link (youtube.com/watch?v=… or youtu.be/…).");
        }
        jdbc.sql("insert into publication (story_id, youtube_url) values (:s, :u)")
                .param("s", storyId).param("u", url.strip()).update();
        status.transition(storyId, StoryStatus.READY_TO_UPLOAD, StoryStatus.PUBLISHED, by, "published: " + url.strip());
    }

    /**
     * A music copyright claim: dispute it with the stored license (stays ready to upload), or send the story back to
     * Stage 4 with that track excluded; the corrected video returns to final review.
     */
    @Transactional
    public void musicClaimed(long storyId, long trackId, boolean dispute, String by) {
        if (dispute) {
            jdbc.sql("insert into copyright_claim (story_id, kind, detail, resolution) values (:s, 'music', :d, 'disputed')")
                    .param("s", storyId).param("d", "track " + trackId).update();
            return;
        }
        Set<String> excluded = new LinkedHashSet<>(List.of(notes.get(storyId, "music-exclude").orElse("").split(",")));
        excluded.add(String.valueOf(trackId));
        excluded.remove("");
        notes.put(storyId, "music-exclude", String.join(",", excluded));
        jdbc.sql("insert into copyright_claim (story_id, kind, detail, resolution) values (:s, 'music', :d, 'replaced')")
                .param("s", storyId).param("d", "track " + trackId).update();
        status.transition(storyId, StoryStatus.READY_TO_UPLOAD, StoryStatus.ASSEMBLING, by,
                "music claim on track " + trackId + "; replacing it");
    }

    /** A visual claim is handled like a final-review rejection with the claim as the reason. */
    @Transactional
    public void visualClaim(long storyId, String claim, String by) {
        if (claim == null || claim.isBlank()) {
            throw InvalidInputException.of("claim", "Paste what the claim says.");
        }
        jdbc.sql("insert into copyright_claim (story_id, kind, detail, resolution) values (:s, 'visual', :d, 'analysed')")
                .param("s", storyId).param("d", claim.strip()).update();
        jdbc.sql("insert into final_rejection (story_id, reason) values (:s, :r)")
                .param("s", storyId).param("r", "Copyright claim: " + claim.strip()).update();
        status.transition(storyId, StoryStatus.READY_TO_UPLOAD, StoryStatus.ANALYZING_REJECTION, by,
                "visual copyright claim");
    }

    @Transactional
    public void kill(long storyId, String by) {
        status.archive(storyId, StoryStatus.READY_TO_UPLOAD, ArchiveReason.KILLED, by, "not published");
    }
}

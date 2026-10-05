package com.storyforge.stage1;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Versioned Stage 1 drafts (story and script). New versions are appended; nothing is overwritten. */
@Component
public class Drafts {

    public enum Kind { STORY, SCRIPT }

    public record Draft(long id, long storyId, Kind kind, int version, String content, String qaResults,
            String promptVersion, String reviewState, String reviewerNotes, Instant createdAt, String sheets) {
    }

    private final JdbcClient jdbc;

    Drafts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Draft add(long storyId, Kind kind, String contentJson, String qaJson, String promptVersion) {
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from story_draft where story_id = :s and kind = :k")
                .param("s", storyId).param("k", kind.name().toLowerCase()).query(Integer.class).single();
        long id = jdbc.sql("""
                insert into story_draft (story_id, kind, version, content, qa_results, prompt_version)
                values (:s, :k, :v, cast(:c as jsonb), cast(:q as jsonb), :p) returning id
                """)
                .param("s", storyId).param("k", kind.name().toLowerCase()).param("v", version)
                .param("c", contentJson).param("q", qaJson).param("p", promptVersion)
                .query(Long.class).single();
        return get(id);
    }

    public Draft get(long id) {
        return jdbc.sql("select * from story_draft where id = :id").param("id", id).query(this::map).single();
    }

    public Optional<Draft> latest(long storyId, Kind kind) {
        return jdbc.sql("select * from story_draft where story_id = :s and kind = :k order by version desc limit 1")
                .param("s", storyId).param("k", kind.name().toLowerCase()).query(this::map).optional();
    }

    public Optional<Draft> approved(long storyId, Kind kind) {
        return jdbc.sql("""
                select * from story_draft where story_id = :s and kind = :k and review_state = 'approved'
                order by version desc limit 1
                """)
                .param("s", storyId).param("k", kind.name().toLowerCase()).query(this::map).optional();
    }

    public List<Draft> all(long storyId, Kind kind) {
        return jdbc.sql("select * from story_draft where story_id = :s and kind = :k order by version desc")
                .param("s", storyId).param("k", kind.name().toLowerCase()).query(this::map).list();
    }

    public void setSheets(long draftId, String sheetsJson) {
        jdbc.sql("update story_draft set sheets = cast(:s as jsonb) where id = :id")
                .param("s", sheetsJson).param("id", draftId).update();
    }

    /** Marks any draft still waiting for review as abandoned (its story was archived). */
    public void abandonPending(long storyId) {
        jdbc.sql("update story_draft set review_state = 'abandoned' where story_id = :s and review_state = 'pending'")
                .param("s", storyId).update();
    }

    public void review(long draftId, String state, String notes) {
        jdbc.sql("update story_draft set review_state = :st, reviewer_notes = :n where id = :id")
                .param("st", state).param("n", notes).param("id", draftId).update();
    }

    private Draft map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Draft(rs.getLong("id"), rs.getLong("story_id"), Kind.valueOf(rs.getString("kind").toUpperCase()),
                rs.getInt("version"), rs.getString("content"), rs.getString("qa_results"),
                rs.getString("prompt_version"), rs.getString("review_state"), rs.getString("reviewer_notes"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("sheets"));
    }
}

package com.storyforge.notes;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reviewer instructions that later attempts must carry, keyed by target: {@code shot:SH07} (added to that shot's
 * image prompt) or {@code mix} (music level). Written by the final-review correction plan.
 */
@Component
public class StoryNotes {

    private final JdbcClient jdbc;

    StoryNotes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void put(long storyId, String target, String note) {
        jdbc.sql("""
                insert into story_note (story_id, target, note) values (:s, :t, :n)
                on conflict (story_id, target) do update set note = excluded.note, created_at = now()
                """).param("s", storyId).param("t", target).param("n", note).update();
    }

    public Optional<String> get(long storyId, String target) {
        return jdbc.sql("select note from story_note where story_id = :s and target = :t")
                .param("s", storyId).param("t", target).query(String.class).optional();
    }
}

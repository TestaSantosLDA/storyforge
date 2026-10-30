package com.storyforge.stage3;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stage 3 shot images and clips. */
@Component
public class ShotStore {

    public record Image(long id, long storyId, String shotId, int attempt, String inputHash, String assetKey,
            String prompt, long seed, String engine, String qa, int failures, boolean passed, boolean accepted) {
    }

    public record Clip(long storyId, String shotId, String inputHash, String assetKey, double durationSec, String qa,
            boolean passed) {
    }

    private final JdbcClient jdbc;

    ShotStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void addImage(long storyId, String shotId, int attempt, String hash, String key, String prompt, long seed,
            String engine, String qa, int failures, boolean passed) {
        jdbc.sql("""
                insert into shot_image (story_id, shot_id, attempt, input_hash, asset_key, prompt, seed, engine, qa,
                    failures, passed) values (:s, :sh, :a, :h, :k, :p, :seed, :e, cast(:qa as jsonb), :f, :ok)
                """)
                .param("s", storyId).param("sh", shotId).param("a", attempt).param("h", hash).param("k", key)
                .param("p", prompt).param("seed", seed).param("e", engine).param("qa", qa).param("f", failures)
                .param("ok", passed).update();
    }

    public List<Image> images(long storyId, String shotId) {
        return jdbc.sql("select * from shot_image where story_id = :s and shot_id = :sh and not superseded order by id")
                .param("s", storyId).param("sh", shotId).query(this::image).list();
    }

    public List<Image> images(long storyId) {
        return jdbc.sql("select * from shot_image where story_id = :s and not superseded order by id").param("s", storyId)
                .query(this::image).list();
    }

    /** The image to animate: a passing one, else one a reviewer accepted. */
    public Optional<Image> usable(long storyId, String shotId) {
        List<Image> all = images(storyId, shotId);
        return all.stream().filter(Image::passed).reduce((a, b) -> b)
                .or(() -> all.stream().filter(Image::accepted).reduce((a, b) -> b));
    }

    /** The draw with the fewest failed checks (the latest among equals). */
    public Optional<Image> best(long storyId, String shotId) {
        return images(storyId, shotId).stream()
                .min(Comparator.comparingInt(Image::failures).thenComparing(Comparator.comparingLong(Image::id).reversed()));
    }

    /** A final-review correction: these shots are drawn again (with the reviewer's instruction). */
    public void supersede(long storyId, java.util.Collection<String> shotIds) {
        for (String id : shotIds) {
            jdbc.sql("update shot_image set superseded = true where story_id = :s and shot_id = :sh")
                    .param("s", storyId).param("sh", id).update();
        }
    }

    public void accept(long imageId) {
        jdbc.sql("update shot_image set accepted = true where id = :id").param("id", imageId).update();
    }

    public void addClip(long storyId, String shotId, String hash, String key, double seconds, String qa, boolean passed) {
        jdbc.sql("""
                insert into shot_clip (story_id, shot_id, input_hash, asset_key, duration_sec, qa, passed)
                values (:s, :sh, :h, :k, :d, cast(:qa as jsonb), :ok)
                """)
                .param("s", storyId).param("sh", shotId).param("h", hash).param("k", key)
                .param("d", BigDecimal.valueOf(seconds)).param("qa", qa).param("ok", passed).update();
    }

    public Optional<Clip> passingClip(String hash) {
        return jdbc.sql("select * from shot_clip where input_hash = :h and passed order by id desc limit 1")
                .param("h", hash).query(this::clip).optional();
    }

    public List<Clip> clips(long storyId) {
        return jdbc.sql("select * from shot_clip where story_id = :s and passed order by id").param("s", storyId)
                .query(this::clip).list();
    }

    private Image image(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Image(rs.getLong("id"), rs.getLong("story_id"), rs.getString("shot_id"), rs.getInt("attempt"),
                rs.getString("input_hash"), rs.getString("asset_key"), rs.getString("prompt"), rs.getLong("seed"),
                rs.getString("engine"), rs.getString("qa"), rs.getInt("failures"), rs.getBoolean("passed"),
                rs.getBoolean("accepted"));
    }

    private Clip clip(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Clip(rs.getLong("story_id"), rs.getString("shot_id"), rs.getString("input_hash"),
                rs.getString("asset_key"), rs.getBigDecimal("duration_sec").doubleValue(), rs.getString("qa"),
                rs.getBoolean("passed"));
    }
}

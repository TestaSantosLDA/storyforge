package com.storyforge.stage2;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stage 2 line attempts and the stitched track. */
@Component
public class AudioLines {

    public record Line(long storyId, String lineId, int attempt, String inputHash, String assetKey, String voice,
            double speed, String engineText, String engine, String transcript, Double matchScore, Double durationSec,
            String qa, boolean passed) {
    }

    public record Track(long storyId, String assetKey, String manifest, double durationSec, boolean durationAccepted) {
    }

    private final JdbcClient jdbc;

    AudioLines(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(Line l) {
        jdbc.sql("""
                insert into audio_line (story_id, line_id, attempt, input_hash, asset_key, voice, speed, engine_text,
                    engine, transcript, match_score, duration_sec, qa, passed)
                values (:s, :l, :a, :h, :k, :v, :sp, :t, :e, :tr, :m, :d, cast(:qa as jsonb), :p)
                """)
                .param("s", l.storyId()).param("l", l.lineId()).param("a", l.attempt()).param("h", l.inputHash())
                .param("k", l.assetKey()).param("v", l.voice()).param("sp", BigDecimal.valueOf(l.speed()))
                .param("t", l.engineText()).param("e", l.engine()).param("tr", l.transcript())
                .param("m", l.matchScore() == null ? null : BigDecimal.valueOf(l.matchScore()))
                .param("d", l.durationSec() == null ? null : BigDecimal.valueOf(l.durationSec()))
                .param("qa", l.qa()).param("p", l.passed()).update();
    }

    /** A passing clip made from exactly these inputs, by any story (never redo what passed). */
    public Optional<Line> passingByHash(String inputHash) {
        return jdbc.sql("select * from audio_line where input_hash = :h and passed order by id desc limit 1")
                .param("h", inputHash).query(this::map).optional();
    }

    public List<Line> forStory(long storyId) {
        return jdbc.sql("select * from audio_line where story_id = :s order by id").param("s", storyId)
                .query(this::map).list();
    }

    public int attempts(long storyId, String lineId) {
        return jdbc.sql("select count(*) from audio_line where story_id = :s and line_id = :l")
                .param("s", storyId).param("l", lineId).query(Integer.class).single();
    }

    public void saveTrack(long storyId, String assetKey, String manifestJson, double durationSec) {
        jdbc.sql("""
                insert into audio_track (story_id, asset_key, manifest, duration_sec) values (:s, :k, cast(:m as jsonb), :d)
                on conflict (story_id) do update set asset_key = excluded.asset_key, manifest = excluded.manifest,
                    duration_sec = excluded.duration_sec, created_at = now()
                """)
                .param("s", storyId).param("k", assetKey).param("m", manifestJson)
                .param("d", BigDecimal.valueOf(durationSec)).update();
    }

    public Optional<Track> track(long storyId) {
        return jdbc.sql("select * from audio_track where story_id = :s").param("s", storyId)
                .query((rs, n) -> new Track(rs.getLong("story_id"), rs.getString("asset_key"), rs.getString("manifest"),
                        rs.getDouble("duration_sec"), rs.getBoolean("duration_accepted"))).optional();
    }

    public boolean durationAccepted(long storyId) {
        return jdbc.sql("select coalesce(bool_or(duration_accepted), false) from audio_track where story_id = :s")
                .param("s", storyId).query(Boolean.class).single();
    }

    public void acceptDuration(long storyId) {
        jdbc.sql("update audio_track set duration_accepted = true where story_id = :s").param("s", storyId).update();
    }

    private static Double dbl(BigDecimal d) {
        return d == null ? null : d.doubleValue();
    }

    private Line map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Line(rs.getLong("story_id"), rs.getString("line_id"), rs.getInt("attempt"),
                rs.getString("input_hash"), rs.getString("asset_key"), rs.getString("voice"), rs.getDouble("speed"),
                rs.getString("engine_text"), rs.getString("engine"), rs.getString("transcript"),
                dbl(rs.getBigDecimal("match_score")), dbl(rs.getBigDecimal("duration_sec")),
                rs.getString("qa"), rs.getBoolean("passed"));
    }
}

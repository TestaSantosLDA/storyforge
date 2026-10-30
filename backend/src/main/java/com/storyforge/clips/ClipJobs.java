package com.storyforge.clips;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage5.Publishing;
import com.storyforge.story.StatusConflictException;
import com.storyforge.validation.InvalidInputException;
import com.storyforge.voice.WordTranscriber.Word;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clip jobs in the database: the form, reads, and every status change. The status columns are the source of truth
 * (CLAUDE.md rule 1) and each change is conditional on the status it expects, so nothing runs twice.
 */
@Service
public class ClipJobs {

    public static final Set<String> REFRAME_MODES = Set.of("blurred_fill", "centre_crop", "crop_offset");
    public static final List<String> PLATFORMS = List.of("youtube", "tiktok", "instagram");

    public record Job(long id, Long sourceVideoId, ClipStatus status, ClipStatus resumeStatus, String attentionReason,
            String url, String uploadKey, String uploadName, String sourceText, boolean ownVideo, String channel,
            int targetClipCount, String notes, boolean spoilersAllowed, String reframeMode, Instant createdAt) {
    }

    public record Source(long id, String origin, String url, String fileKey, String contentHash, String title,
            double durationSec, int width, int height, String language, List<Line> lines, List<Word> words,
            List<Double> sceneCuts) {

        public boolean vertical() {
            return height > width;
        }
    }

    /** One entry of the timing manifest, the shape B1 reads for stories. */
    public record Line(String lineId, double start, double end, String text) {
    }

    public record Clip(long id, long jobId, int index, ClipStatus status, String startLineId, String endLineId,
            double startSec, double endSec, String hookTitle, String reframeMode, Double cropOffset, String videoKey,
            Double durationSec, String qa, int rejections, String reworkNotes) {
    }

    public record Variant(long clipId, String platform, String caption, String hashtags, String cta, String coverKey,
            boolean skipped, String postUrl) {

        public boolean done() {
            return skipped || postUrl != null;
        }
    }

    /** What the page submits. */
    public record Form(String url, String uploadKey, String uploadName, String sourceText, boolean ownVideo,
            String channel, Integer clipCount, String notes, boolean spoilersAllowed, String reframeMode) {
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final StoryforgeProperties props;

    ClipJobs(JdbcClient jdbc, ApplicationEventPublisher events, StoryforgeProperties props) {
        this.jdbc = jdbc;
        this.events = events;
        this.props = props;
    }

    public List<StoryforgeProperties.Clips.ClipChannel> channels() {
        return props.clips().channels().isEmpty()
                ? List.of(new StoryforgeProperties.Clips.ClipChannel("Main channel", null))
                : props.clips().channels();
    }

    public Optional<StoryforgeProperties.Clips.ClipChannel> channel(String name) {
        return channels().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /** Refuses a bad form with field messages; a valid one starts at {@code ingesting}. */
    @Transactional
    public long submit(Form f, String by) {
        boolean hasUrl = f.url() != null && !f.url().isBlank();
        Map<String, String> errors = new LinkedHashMap<>();
        if (hasUrl == (f.uploadKey() != null)) {
            errors.put("video", "Upload a file or paste a YouTube link, not both.");
        } else if (hasUrl && !Publishing.YOUTUBE.matcher(f.url().strip()).matches()) {
            errors.put("video", "That isn't a YouTube video link (youtube.com/watch?v=… or youtu.be/…).");
        }
        if (f.sourceText() == null || f.sourceText().isBlank()) {
            errors.put("sourceText", "Say where the video is from and why you may clip it.");
        }
        if (channel(f.channel()).isEmpty()) {
            errors.put("channel", "Pick one of your channels.");
        }
        int count = f.clipCount() == null ? props.clips().perStory() : f.clipCount();
        if (count < 1 || count > 5) {
            errors.put("clipCount", "Choose 1 to 5 clips.");
        }
        String mode = f.reframeMode() == null || f.reframeMode().isBlank() ? "blurred_fill" : f.reframeMode();
        if (!REFRAME_MODES.contains(mode)) {
            errors.put("reframeMode", "Pick a framing.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        long id = jdbc.sql("""
                insert into clip_job (status, url, upload_key, upload_name, source_text, own_video, channel,
                    target_clip_count, notes, spoilers_allowed, reframe_mode)
                values ('ingesting', :url, :key, :name, :source, :own, :channel, :count, :notes, :spoilers, :mode)
                returning id
                """)
                .param("url", hasUrl ? f.url().strip() : null).param("key", f.uploadKey())
                .param("name", f.uploadName()).param("source", f.sourceText().strip()).param("own", f.ownVideo())
                .param("channel", f.channel()).param("count", count)
                .param("notes", f.notes() == null || f.notes().isBlank() ? null : f.notes().strip())
                .param("spoilers", f.spoilersAllowed()).param("mode", mode).query(Long.class).single();
        log(id, null, null, ClipStatus.INGESTING, by, "form submitted");
        events.publishEvent(new ClipEnteredStatus(id, ClipStatus.INGESTING));
        return id;
    }

    public Job get(long id) {
        return find(id).orElseThrow();
    }

    public Optional<Job> find(long id) {
        return jdbc.sql("select * from clip_job where id = :id").param("id", id).query(this::jobRow).optional();
    }

    public List<Job> all() {
        return jdbc.sql("select * from clip_job where source_video_id is not null or story_id is null "
                + "order by id desc").query(this::jobRow).list();
    }

    public List<Job> working() {
        return jdbc.sql("select * from clip_job order by id").query(this::jobRow).stream()
                .filter(j -> j.status().working()).toList();
    }

    public Optional<Source> source(Long id) {
        return id == null ? Optional.empty() : jdbc.sql("select * from source_video where id = :id").param("id", id)
                .query(this::sourceRow).optional();
    }

    public Optional<Source> sourceByHash(String hash) {
        return jdbc.sql("select * from source_video where content_hash = :h").param("h", hash).query(this::sourceRow)
                .optional();
    }

    public Optional<Source> sourceByUrl(String url) {
        return jdbc.sql("select * from source_video where url = :u").param("u", url).query(this::sourceRow).optional();
    }

    public long addSource(String origin, String url, String fileKey, String hash, String title, double seconds,
            int width, int height, String ytDlpVersion) {
        return jdbc.sql("""
                insert into source_video (origin, url, file_key, content_hash, title, duration_sec, width, height,
                    yt_dlp_version)
                values (:o, :u, :k, :h, :t, :d, :w, :he, :v) returning id
                """)
                .param("o", origin).param("u", url).param("k", fileKey).param("h", hash).param("t", title)
                .param("d", BigDecimal.valueOf(seconds)).param("w", width).param("he", height).param("v", ytDlpVersion)
                .query(Long.class).single();
    }

    public void linkSource(long jobId, long sourceId) {
        jdbc.sql("update clip_job set source_video_id = :s, upload_key = null where id = :id")
                .param("s", sourceId).param("id", jobId).update();
    }

    public void saveTranscript(long sourceId, String language, List<Line> lines, List<Word> words,
            List<Double> sceneCuts, String transcriber) {
        jdbc.sql("""
                update source_video set language = :l, manifest = cast(:m as jsonb), words = cast(:w as jsonb),
                    scene_cuts = cast(:c as jsonb), transcriber = :t where id = :id
                """)
                .param("l", language).param("m", ShotPlanner.JSON.writeValueAsString(lines))
                .param("w", ShotPlanner.JSON.writeValueAsString(words))
                .param("c", ShotPlanner.JSON.writeValueAsString(sceneCuts)).param("t", transcriber)
                .param("id", sourceId).update();
    }

    public List<Clip> clips(long jobId) {
        return jdbc.sql("select * from clip where job_id = :j order by index_in_job").param("j", jobId)
                .query(this::clipRow).list();
    }

    public List<Clip> clips(long jobId, ClipStatus status) {
        return clips(jobId).stream().filter(c -> c.status() == status).toList();
    }

    public Clip clip(long clipId) {
        return jdbc.sql("select * from clip where id = :id").param("id", clipId).query(this::clipRow).single();
    }

    public void addClip(long jobId, int index, Slice s, String reframeMode, String by) {
        long id = jdbc.sql("""
                insert into clip (job_id, index_in_job, status, start_line_id, end_line_id, start_sec, end_sec,
                    hook_title, reframe_mode)
                values (:j, :i, 'rendering', :a, :b, :s, :e, :h, :m) returning id
                """)
                .param("j", jobId).param("i", index).param("a", s.startLineId()).param("b", s.endLineId())
                .param("s", BigDecimal.valueOf(s.start())).param("e", BigDecimal.valueOf(s.end()))
                .param("h", s.hook()).param("m", reframeMode).query(Long.class).single();
        log(jobId, id, null, ClipStatus.RENDERING, by, "selected");
    }

    /** A clip's slice: lines, snapped times and hook title. */
    public record Slice(String startLineId, String endLineId, double start, double end, String hook) {
    }

    public void replaceSlice(long clipId, Slice s, String by) {
        jdbc.sql("""
                update clip set start_line_id = :a, end_line_id = :b, start_sec = :s, end_sec = :e, hook_title = :h,
                    video_key = null, duration_sec = null where id = :id
                """)
                .param("a", s.startLineId()).param("b", s.endLineId()).param("s", BigDecimal.valueOf(s.start()))
                .param("e", BigDecimal.valueOf(s.end())).param("h", s.hook()).param("id", clipId).update();
        clipStatus(clip(clipId), ClipStatus.RENDERING, by, "reselected");
    }

    public void saveRender(long clipId, String videoKey, double seconds, String qa) {
        jdbc.sql("update clip set video_key = :k, duration_sec = :d, qa = cast(:q as jsonb) where id = :id")
                .param("k", videoKey).param("d", BigDecimal.valueOf(seconds)).param("q", qa).param("id", clipId).update();
    }

    public void setHook(long clipId, String hook) {
        jdbc.sql("update clip set hook_title = :h where id = :id").param("h", hook).param("id", clipId).update();
    }

    public void setRework(long clipId, String notes) {
        jdbc.sql("update clip set rework_notes = :n where id = :id").param("n", notes).param("id", clipId).update();
    }

    public void setFraming(long clipId, String mode, Double offset) {
        jdbc.sql("update clip set reframe_mode = :m, crop_offset = :o where id = :id")
                .param("m", mode).param("o", offset == null ? null : BigDecimal.valueOf(offset)).param("id", clipId)
                .update();
    }

    public List<Variant> variants(long clipId) {
        return jdbc.sql("select * from clip_variant where clip_id = :c").param("c", clipId)
                .query((rs, n) -> new Variant(rs.getLong("clip_id"), rs.getString("platform"), rs.getString("caption"),
                        rs.getString("hashtags"), rs.getString("cta"), rs.getString("cover_key"),
                        rs.getBoolean("skipped"), rs.getString("post_url"))).list().stream()
                .sorted(java.util.Comparator.comparingInt(v -> PLATFORMS.indexOf(v.platform()))).toList();
    }

    /** New text for a platform; a skip or a recorded post stays. */
    public void saveVariant(long clipId, String platform, String caption, String hashtags, String cta,
            String coverKey) {
        jdbc.sql("""
                insert into clip_variant (clip_id, platform, caption, hashtags, cta, cover_key)
                values (:c, :p, :t, :h, :a, :k)
                on conflict (clip_id, platform) do update set caption = :t, hashtags = :h, cta = :a, cover_key = :k
                """)
                .param("c", clipId).param("p", platform).param("t", caption).param("h", hashtags).param("a", cta)
                .param("k", coverKey).update();
    }

    public void clipStatus(Clip c, ClipStatus to, String by, String reason) {
        int n = jdbc.sql("update clip set status = :to where id = :id and status = :from")
                .param("to", to.dbValue()).param("id", c.id()).param("from", c.status().dbValue()).update();
        if (n == 0) {
            throw new StatusConflictException("clip " + c.id() + " is no longer " + c.status().dbValue());
        }
        log(c.jobId(), c.id(), c.status(), to, by, reason);
    }

    @Transactional
    public void transition(long id, ClipStatus from, ClipStatus to, String by, String reason) {
        int n = jdbc.sql("""
                update clip_job set status = :to, resume_status = null, attention_reason = null, updated_at = now()
                where id = :id and status = :from
                """).param("to", to.dbValue()).param("id", id).param("from", from.dbValue()).update();
        if (n == 0) {
            throw new StatusConflictException("clip job " + id + " is no longer " + from.dbValue());
        }
        log(id, null, from, to, by, reason);
        events.publishEvent(new ClipEnteredStatus(id, to));
    }

    /** A failure stops and flags (CLAUDE.md rule 5). Resume goes back to the stage that failed. */
    @Transactional
    public void flag(long id, ClipStatus from, String reason, String by) {
        int n = jdbc.sql("""
                update clip_job set status = 'needs_attention', resume_status = :from, attention_reason = :why,
                    updated_at = now() where id = :id and status = :from
                """).param("from", from.dbValue()).param("why", reason).param("id", id).update();
        if (n == 0) {
            throw new StatusConflictException("clip job " + id + " is no longer " + from.dbValue());
        }
        log(id, null, from, ClipStatus.NEEDS_ATTENTION, by, reason);
    }

    @Transactional
    public void resume(long id, String by) {
        Job j = get(id);
        if (j.status() != ClipStatus.NEEDS_ATTENTION) {
            throw new StatusConflictException("clip job " + id + " isn't waiting for attention");
        }
        transition(id, ClipStatus.NEEDS_ATTENTION, j.resumeStatus(), by, "resumed");
    }

    /**
     * Where a job goes after a stage: the earliest stage any of its clips still needs, then clip_ready, then finished.
     * Returns {@code from} unchanged when a clip came back to this same stage, so the caller goes round again.
     */
    @Transactional
    public ClipStatus advance(long id, ClipStatus from, String by) {
        jdbc.sql("select id from clip_job where id = :id for update").param("id", id).query(Long.class).single();
        List<ClipStatus> statuses = new ArrayList<>(clips(id).stream().map(Clip::status).toList());
        ClipStatus next = statuses.stream().filter(ClipStatus::working)
                .min(java.util.Comparator.comparingInt(ClipStatus::ordinal)).orElse(null);
        if (next == null) {
            boolean open = statuses.contains(ClipStatus.CLIP_READY) || statuses.contains(ClipStatus.CLIP_APPROVED);
            next = open ? ClipStatus.CLIP_READY
                    : statuses.contains(ClipStatus.CLIP_PUBLISHED) ? ClipStatus.CLIP_PUBLISHED : ClipStatus.CLIP_KILLED;
        }
        if (next != from) {
            transition(id, from, next, by, "clips moved on");
        }
        return next;
    }

    /** Starts a new file on a flagged job; what the job came from is replaced. */
    @Transactional
    public void replaceFile(long id, String uploadKey, String uploadName, String by) {
        int n = jdbc.sql("""
                update clip_job set upload_key = :k, upload_name = :n, url = null, source_video_id = null
                where id = :id and status = 'needs_attention'
                """).param("k", uploadKey).param("n", uploadName).param("id", id).update();
        if (n == 0) {
            throw new StatusConflictException("clip job " + id + " isn't waiting for attention");
        }
        transition(id, ClipStatus.NEEDS_ATTENTION, ClipStatus.INGESTING, by, "new file uploaded");
    }

    public void addRejection(long clipId, String reason) {
        jdbc.sql("insert into clip_rejection (clip_id, reason) values (:c, :r)").param("c", clipId).param("r", reason)
                .update();
    }

    public Optional<String> latestRejection(long clipId) {
        return jdbc.sql("select reason from clip_rejection where clip_id = :c order by id desc limit 1")
                .param("c", clipId).query(String.class).optional();
    }

    public void routeRejection(long clipId, String target, String notes) {
        jdbc.sql("""
                update clip_rejection set target = :t, notes = :n where id =
                    (select max(id) from clip_rejection where clip_id = :c)
                """).param("t", target).param("n", notes).param("c", clipId).update();
    }

    public int countRejection(long clipId) {
        return jdbc.sql("update clip set rejections = rejections + 1 where id = :id returning rejections")
                .param("id", clipId).query(Integer.class).single();
    }

    public void skip(long clipId, String platform) {
        jdbc.sql("update clip_variant set skipped = true where clip_id = :c and platform = :p")
                .param("c", clipId).param("p", platform).update();
    }

    public void posted(long clipId, String platform, String url) {
        jdbc.sql("update clip_variant set post_url = :u where clip_id = :c and platform = :p")
                .param("u", url).param("c", clipId).param("p", platform).update();
    }

    public void log(long jobId, Long clipId, ClipStatus from, ClipStatus to, String by, String reason) {
        jdbc.sql("""
                insert into clip_status_change (job_id, clip_id, from_status, to_status, triggered_by, reason)
                values (:j, :c, :f, :t, :b, :r)
                """)
                .param("j", jobId).param("c", clipId).param("f", from == null ? null : from.dbValue())
                .param("t", to.dbValue()).param("b", by).param("r", reason).update();
    }

    private Job jobRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Job(rs.getLong("id"), (Long) rs.getObject("source_video_id"),
                ClipStatus.fromDb(rs.getString("status")), ClipStatus.fromDb(rs.getString("resume_status")),
                rs.getString("attention_reason"), rs.getString("url"), rs.getString("upload_key"),
                rs.getString("upload_name"), rs.getString("source_text"), rs.getBoolean("own_video"),
                rs.getString("channel"), rs.getInt("target_clip_count"), rs.getString("notes"),
                rs.getBoolean("spoilers_allowed"), rs.getString("reframe_mode"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Source sourceRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Source(rs.getLong("id"), rs.getString("origin"), rs.getString("url"), rs.getString("file_key"),
                rs.getString("content_hash"), rs.getString("title"), rs.getBigDecimal("duration_sec").doubleValue(),
                rs.getInt("width"), rs.getInt("height"), rs.getString("language"),
                list(rs.getString("manifest"), Line[].class), list(rs.getString("words"), Word[].class),
                list(rs.getString("scene_cuts"), Double[].class));
    }

    private static <T> List<T> list(String json, Class<T[]> type) {
        return json == null ? null : List.of(ShotPlanner.JSON.readValue(json, type));
    }

    private Clip clipRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        BigDecimal offset = rs.getBigDecimal("crop_offset");
        BigDecimal seconds = rs.getBigDecimal("duration_sec");
        return new Clip(rs.getLong("id"), rs.getLong("job_id"), rs.getInt("index_in_job"),
                ClipStatus.fromDb(rs.getString("status")), rs.getString("start_line_id"), rs.getString("end_line_id"),
                rs.getBigDecimal("start_sec").doubleValue(), rs.getBigDecimal("end_sec").doubleValue(),
                rs.getString("hook_title"), rs.getString("reframe_mode"), offset == null ? null : offset.doubleValue(),
                rs.getString("video_key"), seconds == null ? null : seconds.doubleValue(), rs.getString("qa"),
                rs.getInt("rejections"), rs.getString("rework_notes"));
    }
}

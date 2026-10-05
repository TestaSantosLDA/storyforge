package com.storyforge.stage5;

import com.storyforge.character.CharacterStore;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.QaCheck;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage1.StoryContent;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage3.ShotPlan;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage3.ShotStore;
import com.storyforge.stage4.MusicLibrary;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Step 5A: title, description with chapters, tags and three thumbnails, checked by code and by Claude (honesty,
 * trademarks, readable thumbnail text). Up to 3 tries, then needs attention.
 */
@Component
public class PublishStep {

    static final String TRIGGER = "system:stage5-publish";
    private static final Logger log = LoggerFactory.getLogger(PublishStep.class);

    /** What Claude writes (prompts/schemas/metadata-v1.json). */
    public record Generated(String title, String hook, String summary, String moralLine, List<String> chapterTitles,
            List<String> tags, List<String> hashtags, List<Thumb> thumbnails) {
        public record Thumb(String shotId, String text) {
        }
    }

    /** The package shown on the publish screen; text fields are editable there. */
    public record Metadata(String title, String description, List<String> tags, List<Chapter> chapters,
            String playlist) {
        public record Chapter(String time, String title) {
        }
    }

    public record Thumbnail(String shotId, String text, String assetKey) {
    }

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final Drafts drafts;
    private final AudioLines audio;
    private final ShotStore shots;
    private final CharacterStore characters;
    private final MusicLibrary music;
    private final AssetStorage assets;
    private final AiCalls ai;
    private final JdbcClient jdbc;
    private final StoryforgeProperties props;

    PublishStep(StoryRepository stories, StoryStatusService status, Drafts drafts, AudioLines audio, ShotStore shots,
            CharacterStore characters, MusicLibrary music, AssetStorage assets, AiCalls ai, JdbcClient jdbc,
            StoryforgeProperties props) {
        this.stories = stories;
        this.status = status;
        this.drafts = drafts;
        this.audio = audio;
        this.shots = shots;
        this.characters = characters;
        this.music = music;
        this.assets = assets;
        this.ai = ai;
        this.jdbc = jdbc;
        this.props = props;
    }

    public void run(long storyId) {
        Story story = stories.findWithTopic(storyId).orElseThrow();
        if (story.getStatus() != StoryStatus.PREPARING_PUBLISH) {
            return;
        }
        String notes = jdbc.sql("select notes from publish_package where story_id = :s order by version desc limit 1")
                .param("s", storyId).query(String.class).optional().orElse(null);
        String feedback = "";
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                Prepared p = prepare(story, notes, feedback);
                List<QaCheck> checks = p == null ? List.of(QaCheck.code("format", false, "no valid metadata"))
                        : checks(story, p);
                boolean passed = QaCheck.allPass(checks);
                if (p != null) {
                    save(storyId, p, checks, passed, notes);
                }
                if (passed) {
                    status.transition(storyId, StoryStatus.PREPARING_PUBLISH, StoryStatus.READY_TO_UPLOAD, TRIGGER,
                            "publish package ready: " + p.metadata().title());
                    return;
                }
                feedback = QaCheck.feedback(checks);
                log.info("story {} metadata QA failed (try {}): {}", storyId, attempt, feedback);
            }
            status.flag(storyId, StoryStatus.PREPARING_PUBLISH, "Metadata QA failed 3 times: " + feedback, TRIGGER);
        } catch (LlmUnavailableException e) {
            flag(storyId, "Claude unavailable after retries: " + e.getMessage());
        } catch (StatusConflictException e) {
            log.info("story {} moved on during publishing: {}", storyId, e.getMessage());
        }
    }

    record Prepared(Metadata metadata, List<Thumbnail> thumbnails, Generated generated) {
    }

    private Prepared prepare(Story story, String notes, String feedback) {
        long id = story.getId();
        StoryContent sc = ShotPlanner.JSON.readValue(drafts.approved(id, Drafts.Kind.STORY).orElseThrow().content(),
                StoryContent.class);
        ScriptContent script = ShotPlanner.JSON.readValue(drafts.approved(id, Drafts.Kind.SCRIPT).orElseThrow()
                .content(), ScriptContent.class);
        AudioLines.Track track = audio.track(id).orElseThrow();
        Map<String, double[]> timing = ShotPlanner.timing(track.manifest());
        ShotPlan plan = ShotPlanner.time(ShotPlanner.JSON.readValue(
                drafts.approved(id, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class), timing, track.durationSec());
        JsonNode assembly = jdbc.sql("select row_to_json(a) from assembly a where story_id = :s order by version desc limit 1")
                .param("s", id).query(String.class).optional().map(ShotPlanner.JSON::readTree).orElseThrow();
        double intro = assembly.path("intro_sec").asDouble();

        List<String> castIds = new ArrayList<>();
        sc.reusedCharacters().forEach(r -> castIds.add(r.id()));
        sc.newCharacters().forEach(n -> castIds.add(n.id()));
        String cast = castIds.stream().map(characters::get).flatMap(Optional::stream)
                .map(c -> "- " + c.name() + ": " + c.visualDescription()).collect(Collectors.joining("\n"));
        String scenes = script.scenes().stream().map(s -> "- " + s.id() + " (" + s.mood() + "): " + s.visualDescription())
                .collect(Collectors.joining("\n"));
        String shotList = plan.shots().stream().filter(s -> shots.usable(id, s.id()).isPresent())
                .map(s -> "- " + s.id() + ": " + s.setting() + " "
                        + s.actions().stream().map(ShotPlan.Action::action).collect(Collectors.joining(" ")))
                .collect(Collectors.joining("\n"));
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("series", story.getTopic().getName());
        vars.put("story_title", sc.title());
        vars.put("moral", sc.moral());
        vars.put("beats", sc.beats().stream().map(b -> "- " + b).collect(Collectors.joining("\n")));
        vars.put("scenes", scenes);
        vars.put("characters", cast.isEmpty() ? "(narrator only)" : cast);
        vars.put("shots", shotList);
        String extra = (notes == null || notes.isBlank() ? "" : "\n## Reviewer's notes\n" + notes)
                + (feedback.isBlank() ? "" : "\n## Fix these problems from the last try\n" + feedback);
        vars.put("notes", extra);
        AiCalls.Outcome o = ai.call(id, "stage5", "metadata", "metadata-v1", "metadata-v1", vars);
        if (!o.ok()) {
            return null;
        }
        Generated g = ShotPlanner.JSON.readValue(o.json(), Generated.class);

        List<Metadata.Chapter> chapters = chapters(script, timing, intro, g.chapterTitles());
        String attribution = musicAttribution(assembly.path("music"));
        String playlist = props.channel().playlists().getOrDefault(story.getTopic().getName(), "");
        StringBuilder d = new StringBuilder();
        d.append(g.hook().strip()).append("\n\n").append(g.summary().strip()).append("\n\n")
                .append(g.moralLine().strip()).append("\n\n");
        if (!chapters.isEmpty()) {
            d.append("Chapters\n");
            chapters.forEach(c -> d.append(c.time()).append(' ').append(c.title()).append('\n'));
        }
        if (!playlist.isBlank()) {
            d.append("\nMore from ").append(story.getTopic().getName()).append(": ").append(playlist).append('\n');
        }
        if (!attribution.isBlank()) {
            d.append("\nMusic\n").append(attribution).append('\n');
        }
        if (!props.channel().links().isEmpty()) {
            d.append('\n');
            props.channel().links().forEach(l -> d.append(l).append('\n'));
        }
        if (!g.hashtags().isEmpty()) {
            d.append('\n').append(String.join(" ", g.hashtags().stream().limit(3).toList()));
        }
        Metadata metadata = new Metadata(g.title().strip(), d.toString().strip(), g.tags(), chapters, playlist);

        List<Thumbnail> thumbs = new ArrayList<>();
        int n = 0;
        for (Generated.Thumb t : g.thumbnails()) {
            n++;
            var image = shots.usable(id, t.shotId());
            if (image.isEmpty()) {
                continue;
            }
            String key = "stories/" + id + "/publish/thumbnail-" + System.currentTimeMillis() + "-" + n + ".jpg";
            put(key, Thumbnails.render(read(image.get().assetKey()), t.text()));
            thumbs.add(new Thumbnail(t.shotId(), t.text(), key));
        }
        return new Prepared(metadata, thumbs, g);
    }

    /**
     * One chapter per scene at its start in the final video, the first at 0:00; scenes under 10 s join the previous one.
     * None at all when fewer than 3 remain.
     */
    static List<Metadata.Chapter> chapters(ScriptContent script, Map<String, double[]> timing, double intro,
            List<String> titles) {
        List<double[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < script.scenes().size(); i++) {
            double start = i == 0 ? 0 : intro + timing.get(script.scenes().get(i).lineIds().getFirst())[0];
            String title = i < titles.size() ? titles.get(i).strip() : "Part " + (i + 1);
            if (!starts.isEmpty() && start - starts.getLast()[0] < 10) {
                continue;
            }
            starts.add(new double[] {start});
            names.add(title);
        }
        List<Metadata.Chapter> out = new ArrayList<>();
        if (starts.size() < 3) {
            return out; // YouTube ignores fewer than 3 chapters, so a short story goes without
        }
        for (int i = 0; i < starts.size(); i++) {
            out.add(new Metadata.Chapter(clock(starts.get(i)[0]), names.get(i)));
        }
        return out;
    }

    private String musicAttribution(JsonNode used) {
        List<String> lines = new ArrayList<>();
        for (JsonNode m : used) {
            MusicLibrary.Track t = music.get(m.path("track_id").asLong());
            if (t.attribution() != null) {
                lines.add(t.attribution());
            }
        }
        return lines.stream().distinct().collect(Collectors.joining("\n"));
    }

    /** The Step 5A QA table: code checks, then Claude for honesty, trademarks and thumbnail readability. */
    List<QaCheck> checks(Story story, Prepared p) {
        Metadata m = p.metadata();
        List<QaCheck> checks = new ArrayList<>();
        int len = m.title().length();
        checks.add(QaCheck.code("title", len <= 100 && len >= 30 && len <= 70,
                len + " characters; aim for 50–60 (YouTube cuts long titles)"));
        boolean chaptersOk = m.chapters().isEmpty()
                || m.chapters().size() >= 3 && m.chapters().getFirst().time().equals("0:00");
        checks.add(QaCheck.code("chapters", chaptersOk, m.chapters().isEmpty() ? "fewer than 3 scenes, so no chapters"
                : m.chapters().size() + " chapters; need at least 3 starting at 0:00"));
        checks.add(QaCheck.code("description", m.description().length() <= 5000 && p.generated().hook().length() > 10,
                "needs a hook and must fit YouTube's 5000 characters"));
        int tagChars = m.tags().stream().mapToInt(t -> t.length() + (t.contains(" ") ? 2 : 0)).sum() + Math.max(0, m.tags().size() - 1);
        checks.add(QaCheck.code("tags", tagChars <= 500, tagChars + " characters; YouTube allows 500"));
        boolean thumbsOk = p.thumbnails().size() == 3 && p.thumbnails().stream().allMatch(t -> {
            var path = assets.localPath(t.assetKey()).orElse(null);
            try {
                return path != null && java.nio.file.Files.size(path) <= Thumbnails.MAX_BYTES;
            } catch (IOException e) {
                return false;
            }
        });
        checks.add(QaCheck.code("thumbnails", thumbsOk, "need 3 thumbnails of 1280×720 under 2 MB, from drawn shots"));
        if (!QaCheck.allPass(checks)) {
            return checks;
        }
        List<Path> files = p.thumbnails().stream().map(t -> assets.localPath(t.assetKey()).orElseThrow()).toList();
        StoryContent sc = ShotPlanner.JSON.readValue(drafts.approved(story.getId(), Drafts.Kind.STORY).orElseThrow()
                .content(), StoryContent.class);
        checks.addAll(ai.judge(story.getId(), "stage5", "metadata_qa", "metadata-qa-v1", Map.of(
                "story", String.join(" ", sc.beats()) + " Moral: " + sc.moral(),
                "title", m.title(), "description", m.description(), "tags", String.join(", ", m.tags()),
                "thumbnails", files.stream().map(f -> "- " + f.toAbsolutePath()).collect(Collectors.joining("\n"))),
                files));
        return checks;
    }

    private void save(long storyId, Prepared p, List<QaCheck> checks, boolean passed, String notes) {
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from publish_package where story_id = :s")
                .param("s", storyId).query(Integer.class).single();
        jdbc.sql("""
                insert into publish_package (story_id, version, metadata, thumbnails, qa, passed, notes)
                values (:s, :v, cast(:m as jsonb), cast(:t as jsonb), cast(:q as jsonb), :p, :n)
                """)
                .param("s", storyId).param("v", version).param("m", ShotPlanner.JSON.writeValueAsString(p.metadata()))
                .param("t", ShotPlanner.JSON.writeValueAsString(p.thumbnails()))
                .param("q", ShotPlanner.JSON.writeValueAsString(checks)).param("p", passed).param("n", notes).update();
    }

    private void flag(long storyId, String reason) {
        try {
            status.flag(storyId, StoryStatus.PREPARING_PUBLISH, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("story {} not flagged: {}", storyId, e.getMessage());
        }
    }

    private void put(String key, byte[] data) {
        try {
            assets.put(key, new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] read(String key) {
        try (InputStream in = assets.open(key).orElseThrow()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String clock(double seconds) {
        long s = (long) Math.floor(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}

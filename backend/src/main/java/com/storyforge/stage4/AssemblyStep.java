package com.storyforge.stage4;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.notes.StoryNotes;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage3.ShotPlan;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage3.ShotStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Stage 4 assembly: music per scene, timeline (intro, shots, end card), ducked mix normalised for YouTube,
 * subtitles from the exact script text, render, then the automated QA. Deterministic, so a QA failure is retried
 * once and a second failure is treated as a bug.
 */
@Component
public class AssemblyStep {

    static final String TRIGGER = "system:stage4-assembly";
    private static final Logger log = LoggerFactory.getLogger(AssemblyStep.class);

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final Drafts drafts;
    private final AudioLines audio;
    private final ShotStore shots;
    private final MusicLibrary music;
    private final AssetStorage assets;
    private final StoryNotes notes;
    private final JdbcClient jdbc;
    private final StoryforgeProperties props;
    private final Assembler assembler;

    AssemblyStep(StoryRepository stories, StoryStatusService status, Drafts drafts, AudioLines audio, ShotStore shots,
            MusicLibrary music, AssetStorage assets, StoryNotes notes, JdbcClient jdbc, StoryforgeProperties props) {
        this.stories = stories;
        this.status = status;
        this.drafts = drafts;
        this.audio = audio;
        this.shots = shots;
        this.music = music;
        this.assets = assets;
        this.notes = notes;
        this.jdbc = jdbc;
        this.props = props;
        this.assembler = new Assembler(props);
    }

    public void run(long storyId) {
        Story story = stories.findById(storyId).orElseThrow();
        if (story.getStatus() != StoryStatus.ASSEMBLING) {
            return;
        }
        String lastProblem = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Map<String, Object>> qa = assembleOnce(storyId);
                boolean passed = qa.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
                if (passed) {
                    status.transition(storyId, StoryStatus.ASSEMBLING, StoryStatus.AWAITING_FINAL_REVIEW, TRIGGER,
                            "assembled; ready for final review");
                    return;
                }
                lastProblem = qa.stream().filter(c -> !Boolean.TRUE.equals(c.get("pass")))
                        .map(c -> c.get("name") + ": " + c.get("detail")).reduce((a, b) -> a + "; " + b).orElse("");
                log.warn("story {} assembly QA failed (try {}): {}", storyId, attempt, lastProblem);
            } catch (Assembler.AssemblyException e) {
                lastProblem = e.getMessage();
            } catch (StatusConflictException e) {
                return;
            }
        }
        try {
            status.flag(storyId, StoryStatus.ASSEMBLING, "Assembly failed twice (treated as a bug): " + lastProblem,
                    TRIGGER);
        } catch (StatusConflictException ignored) {
            // moved on
        }
    }

    /** One render with its QA; the result is stored as a new assembly version. */
    List<Map<String, Object>> assembleOnce(long storyId) {
        var v = props.visuals();
        ScriptContent script = ShotPlanner.JSON.readValue(
                drafts.approved(storyId, Drafts.Kind.SCRIPT).orElseThrow().content(), ScriptContent.class);
        AudioLines.Track track = audio.track(storyId).orElseThrow();
        Map<String, double[]> timing = ShotPlanner.timing(track.manifest());
        ShotPlan plan = ShotPlanner.time(ShotPlanner.JSON.readValue(
                drafts.approved(storyId, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class),
                timing, track.durationSec());

        Map<String, ShotStore.Clip> clipByShot = new HashMap<>();
        shots.clips(storyId).forEach(c -> clipByShot.put(c.shotId(), c));
        List<Path> clips = new ArrayList<>();
        List<String> syncProblems = new ArrayList<>();
        double t = 0;
        double frame = 1.0 / v.fps();
        for (ShotPlan.Shot s : plan.shots()) {
            ShotStore.Clip c = clipByShot.get(s.id());
            if (c == null) {
                throw new Assembler.AssemblyException("shot " + s.id() + " has no clip");
            }
            if (Math.abs(t - s.start()) > frame + 1e-6) {
                syncProblems.add(String.format(Locale.ROOT, "%s starts at %.2fs, manifest says %.2fs", s.id(), t, s.start()));
            }
            t += c.durationSec();
            clips.add(local(c.assetKey()));
        }

        Path intro = brandFile(props.brand().intro());
        Path endCard = brandFile(props.brand().endCard());
        double introSec = intro == null ? 0 : assembler.duration(intro);
        double endSec = endCard == null ? 0 : assembler.duration(endCard);

        List<MusicChoice> choices = chooseMusic(storyId, script, timing, introSec);
        double volume = notes.get(storyId, "mix").map(Double::parseDouble).orElse(props.brand().musicVolumeDb());
        List<Assembler.MusicSegment> segments = choices.stream()
                .map(m -> new Assembler.MusicSegment(local(m.track().assetKey()), m.start(), m.end())).toList();

        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from assembly where story_id = :s")
                .param("s", storyId).query(Integer.class).single();
        String videoKey = "stories/" + storyId + "/video/v" + version + ".mp4";
        String srtKey = "stories/" + storyId + "/video/v" + version + ".srt";
        Path tmp;
        try {
            tmp = Files.createTempFile("storyforge-video-", ".mp4");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            assembler.render(new Assembler.Plan(intro, introSec, clips, endCard, endSec, local(track.assetKey()),
                    track.durationSec(), segments, volume), tmp);

            List<Subtitles.Spoken> spoken = new ArrayList<>();
            for (ScriptContent.Line l : script.lines()) {
                double[] tm = timing.get(l.id());
                spoken.add(new Subtitles.Spoken(l.text(), introSec + tm[0], introSec + tm[1]));
            }
            List<Subtitles.Cue> cues = Subtitles.cues(spoken);
            put(srtKey, Subtitles.srt(cues).getBytes(StandardCharsets.UTF_8));

            double expected = introSec + track.durationSec() + endSec;
            Assembler.Probe probe = assembler.probe(tmp);
            double lufs = assembler.loudness(tmp);
            List<Double> black = assembler.blackStarts(tmp).stream()
                    .filter(b -> b > 1.0 && b < expected - 1.0).toList(); // fades at the very start/end are intended
            List<String> subtitleProblems = subtitleProblems(cues, script);
            List<String> unlicensed = choices.stream().filter(m -> !music.licensed(m.track()))
                    .map(m -> m.track().title()).distinct().toList();

            List<Map<String, Object>> qa = List.of(
                    check("duration", Math.abs(probe.seconds() - expected) <= frame + 0.05,
                            String.format(Locale.ROOT, "%.2fs, expected %.2fs", probe.seconds(), expected)),
                    check("sync", syncProblems.isEmpty(), syncProblems.isEmpty() ? "every shot on its manifest time"
                            : String.join("; ", syncProblems)),
                    check("frames", black.isEmpty(), black.isEmpty() ? "no black frames" : "black at " + black),
                    check("loudness", lufs >= -15.5 && lufs <= -12.5,
                            String.format(Locale.ROOT, "%.1f LUFS (target -14)", lufs)),
                    check("ducking", true, segments.isEmpty() ? "no music"
                            : "music bed compressed by the narration (sidechain)"),
                    check("subtitles", subtitleProblems.isEmpty(), subtitleProblems.isEmpty()
                            ? cues.size() + " cues" : String.join("; ", subtitleProblems)),
                    check("music_licenses", unlicensed.isEmpty(), unlicensed.isEmpty()
                            ? choices.size() + " music segment(s), all licensed" : "no license record: " + unlicensed),
                    check("file", "h264".equals(probe.videoCodec()) && "aac".equals(probe.audioCodec())
                                    && probe.width() == v.outputWidth() && probe.height() == v.outputHeight()
                                    && Math.abs(probe.fps() - v.fps()) < 0.01,
                            probe.videoCodec() + "/" + probe.audioCodec() + " " + probe.width() + "x" + probe.height()
                                    + " @ " + probe.fps()));
            boolean passed = qa.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
            try (InputStream in = Files.newInputStream(tmp)) {
                assets.put(videoKey, in);
            }
            List<Map<String, Object>> musicJson = choices.stream().map(m -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("track_id", m.track().id());
                e.put("title", m.track().title());
                e.put("license", m.track().licenseType());
                e.put("scenes", m.scenes());
                e.put("start", round(m.start()));
                e.put("end", round(m.end()));
                return e;
            }).toList();
            jdbc.sql("""
                    insert into assembly (story_id, version, video_key, srt_key, duration_sec, intro_sec, music, qa, passed)
                    values (:s, :v, :k, :srt, :d, :i, cast(:m as jsonb), cast(:qa as jsonb), :p)
                    """)
                    .param("s", storyId).param("v", version).param("k", videoKey).param("srt", srtKey)
                    .param("d", java.math.BigDecimal.valueOf(probe.seconds()))
                    .param("i", java.math.BigDecimal.valueOf(introSec))
                    .param("m", ShotPlanner.JSON.writeValueAsString(musicJson))
                    .param("qa", ShotPlanner.JSON.writeValueAsString(qa)).param("p", passed).update();
            return qa;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    /** One track under consecutive scenes of the same mood, with the scenes' time range in the final video. */
    record MusicChoice(MusicLibrary.Track track, List<String> scenes, double start, double end) {
    }

    /**
     * A track per run of same-mood scenes; avoids tracks used by the previously assembled story and, within this
     * story, prefers a different track at each mood change. Unlicensed tracks are skipped (scenario 10). No matching
     * track: that stretch has no music.
     */
    List<MusicChoice> chooseMusic(long storyId, ScriptContent script, Map<String, double[]> timing, double introSec) {
        List<MusicLibrary.Track> library = music.all().stream().filter(music::licensed).toList();
        if (library.isEmpty()) {
            return List.of();
        }
        Set<Long> lastStory = new HashSet<>();
        jdbc.sql("""
                select music from assembly where story_id <> :s and music is not null order by id desc limit 1
                """).param("s", storyId).query(String.class).optional().ifPresent(json -> {
                    for (JsonNode n : ShotPlanner.JSON.readTree(json)) {
                        lastStory.add(n.path("track_id").asLong());
                    }
                });
        Set<String> excluded = new HashSet<>(List.of(notes.get(storyId, "music-exclude").orElse("").split(",")));
        List<MusicChoice> out = new ArrayList<>();
        Set<Long> usedHere = new HashSet<>();
        int i = 0;
        List<ScriptContent.Scene> scenes = script.scenes();
        while (i < scenes.size()) {
            String mood = scenes.get(i).mood();
            int j = i;
            while (j + 1 < scenes.size() && scenes.get(j + 1).mood().equals(mood)) {
                j++;
            }
            double start = introSec + timing.get(scenes.get(i).lineIds().getFirst())[0];
            double end = introSec + timing.get(scenes.get(j).lineIds().getLast())[1];
            List<String> ids = scenes.subList(i, j + 1).stream().map(ScriptContent.Scene::id).toList();
            Optional<MusicLibrary.Track> pick = library.stream()
                    .filter(tr -> tr.moods().contains(mood) && !excluded.contains(String.valueOf(tr.id())))
                    .min((a, b) -> Integer.compare(score(a, lastStory, usedHere), score(b, lastStory, usedHere)));
            pick.ifPresent(tr -> {
                out.add(new MusicChoice(tr, ids, start, end));
                usedHere.add(tr.id());
            });
            i = j + 1;
        }
        return out;
    }

    private static int score(MusicLibrary.Track t, Set<Long> lastStory, Set<Long> usedHere) {
        return (lastStory.contains(t.id()) ? 2 : 0) + (usedHere.contains(t.id()) ? 1 : 0);
    }

    static List<String> subtitleProblems(List<Subtitles.Cue> cues, ScriptContent script) {
        List<String> problems = new ArrayList<>();
        String all = String.join(" ", cues.stream().flatMap(c -> c.rows().stream()).toList()).replaceAll("\\s+", " ");
        for (ScriptContent.Line l : script.lines()) {
            if (!all.contains(l.text().strip().replaceAll("\\s+", " "))) {
                problems.add(l.id() + " missing");
            }
        }
        for (int k = 0; k < cues.size(); k++) {
            Subtitles.Cue c = cues.get(k);
            if (c.rows().stream().anyMatch(r -> r.length() > Subtitles.MAX_CHARS) || c.rows().size() > 2) {
                problems.add("cue " + (k + 1) + " too long");
            }
            if (k > 0 && c.start() < cues.get(k - 1).end() - 1e-6) {
                problems.add("cue " + (k + 1) + " overlaps");
            }
        }
        return problems;
    }

    private Path brandFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path p = Path.of(path);
        return Files.isRegularFile(p) ? p : null;
    }

    private Path local(String key) {
        return assets.localPath(key).orElseThrow(() -> new Assembler.AssemblyException("missing asset " + key));
    }

    private void put(String key, byte[] data) {
        try {
            assets.put(key, new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> check(String name, boolean pass, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pass", pass);
        m.put("detail", detail);
        return m;
    }

    private static double round(double s) {
        return Math.round(s * 1000) / 1000.0;
    }
}

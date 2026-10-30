package com.storyforge.stage2;

import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage1.StoryContent;
import com.storyforge.storage.AssetStorage;
import com.storyforge.storage.InputHash;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import com.storyforge.voice.SpeechClip;
import com.storyforge.voice.SpeechUnavailableException;
import com.storyforge.voice.Transcriber;
import com.storyforge.voice.VoiceEngine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 2: one clip per script line in the speaker's voice, each checked by transcribing it back; then the clips are
 * stitched with pauses into one track with a timing manifest. Passing clips are reused by input hash, so a re-run
 * only makes what is missing or failed.
 */
@Component
public class AudioStep {

    static final String TRIGGER = "system:stage2-audio";
    /** Lines this short are judged by length, not words per second. */
    static final int SHORT_LINE_WORDS = 3;
    private static final Logger log = LoggerFactory.getLogger(AudioStep.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final Drafts drafts;
    private final CharacterStore characters;
    private final VoiceEngine voice;
    private final Transcriber transcriber;
    private final AudioLines lines;
    private final Pronunciation pronunciation;
    private final AssetStorage assets;
    private final StoryforgeProperties props;

    AudioStep(StoryRepository stories, StoryStatusService status, Drafts drafts, CharacterStore characters,
            VoiceEngine voice, Transcriber transcriber, AudioLines lines, Pronunciation pronunciation,
            AssetStorage assets, StoryforgeProperties props) {
        this.stories = stories;
        this.status = status;
        this.drafts = drafts;
        this.characters = characters;
        this.voice = voice;
        this.transcriber = transcriber;
        this.lines = lines;
        this.pronunciation = pronunciation;
        this.assets = assets;
        this.props = props;
    }

    /** One clip that passed, ready to stitch. */
    record Clip(ScriptContent.Line line, String scene, String assetKey) {
    }

    public void run(long storyId) {
        Story story = stories.findById(storyId).orElseThrow();
        if (story.getStatus() != StoryStatus.AUDIO_IN_PROGRESS) {
            return;
        }
        ScriptContent script = JSON.readValue(drafts.approved(storyId, Drafts.Kind.SCRIPT).orElseThrow().content(),
                ScriptContent.class);
        StoryContent storyContent = JSON.readValue(drafts.approved(storyId, Drafts.Kind.STORY).orElseThrow()
                .content(), StoryContent.class);

        Map<String, String> voices = new HashMap<>();
        Map<String, CharacterFile> byId = new HashMap<>();
        characters.all().forEach(c -> byId.put(c.id(), c));
        voices.put(ScriptContent.NARRATOR, props.voices().narrator());
        Set<String> names = new LinkedHashSet<>();
        for (ScriptContent.Line l : script.lines()) {
            if (!voices.containsKey(l.speaker())) {
                CharacterFile c = byId.get(l.speaker());
                Object v = c == null || c.voice() == null ? null : c.voice().get("id");
                if (v == null) {
                    // Scenario 7: should be impossible after Gate A; flag before generating anything.
                    flag(storyId, "speaker \"" + l.speaker() + "\" has no voice assigned");
                    return;
                }
                voices.put(l.speaker(), String.valueOf(v));
                names.add(c.name());
            }
        }
        storyContent.newCharacters().forEach(n -> names.add(n.name()));

        Map<String, String> sceneOf = new HashMap<>();
        script.scenes().forEach(sc -> sc.lineIds().forEach(id -> sceneOf.put(id, sc.id())));

        List<Clip> clips = new ArrayList<>();
        Map<String, String> failing = new LinkedHashMap<>();
        try {
            for (ScriptContent.Line l : script.lines()) {
                if (stories.findById(storyId).orElseThrow().getStatus() != StoryStatus.AUDIO_IN_PROGRESS) {
                    return;
                }
                LineResult r = makeLine(storyId, l, voices.get(l.speaker()), names);
                if (r.assetKey() == null) {
                    failing.put(l.id(), r.problem());
                } else {
                    clips.add(new Clip(l, sceneOf.get(l.id()), r.assetKey()));
                }
            }
        } catch (SpeechUnavailableException e) {
            flag(storyId, "Voice sidecar unavailable: " + e.getMessage() + ". Start the sidecar, then Resume.");
            return;
        }
        if (!failing.isEmpty()) {
            StringBuilder why = new StringBuilder(failing.size() + " line(s) failed 3 times: ");
            failing.forEach((id, p) -> why.append(id).append(" (").append(p).append("); "));
            flag(storyId, why.toString().strip());
            return;
        }
        stitch(storyId, story.getTargetLengthMin(), clips);
    }

    record LineResult(String assetKey, String problem) {
    }

    private LineResult makeLine(long storyId, ScriptContent.Line l, String voiceId, Set<String> names) {
        String text = pronunciation.apply(l.text());
        List<Double> speeds = props.audio().attemptSpeeds();
        int max = props.audio().retriesPerLine();
        String lastProblem = "";
        for (int attempt = 1; attempt <= max; attempt++) {
            double speed = speeds.get(Math.min(attempt - 1, speeds.size() - 1));
            String hash = InputHash.of("tts", voiceId, String.valueOf(speed), text);
            var cached = lines.passingByHash(hash);
            if (cached.isPresent() && assets.exists(cached.get().assetKey())) {
                return new LineResult(cached.get().assetKey(), null);
            }
            SpeechClip clip = withBackoff(() -> voice.synthesize(text, voiceId, speed));
            String key = "audio/clips/" + hash.substring(0, 2) + "/" + hash + ".wav";
            put(key, clip.wav());
            String heard = withBackoff(() -> transcriber.transcribe(clip.wav())).text();
            double match = TranscriptMatch.score(l.text(), heard, names);
            List<Map<String, Object>> qa = checks(l.text(), clip, match, voiceId);
            boolean passed = qa.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")));
            lines.add(new AudioLines.Line(storyId, l.id(), lines.attempts(storyId, l.id()) + 1, hash, key, voiceId,
                    speed, text, clip.engineVersion(), heard, match, clip.durationSec(), JSON.writeValueAsString(qa),
                    passed));
            if (passed) {
                return new LineResult(key, null);
            }
            lastProblem = qa.stream().filter(c -> !Boolean.TRUE.equals(c.get("pass")))
                    .map(c -> c.get("name") + ": " + c.get("detail")).reduce((a, b) -> a + ", " + b).orElse("");
            log.info("story {} line {} attempt {} failed: {}", storyId, l.id(), attempt, lastProblem);
        }
        return new LineResult(null, lastProblem);
    }

    /**
     * The per-line QA table from the Stage 2 doc. Very short lines ("Oh.", "Very atmospheric.") get two allowances
     * found in the first real run: Whisper can't reliably transcribe a lone interjection ("Oh." heard as "Though?"),
     * and the engine's lead-in and tail dominate the duration, so words-per-second means nothing there.
     */
    List<Map<String, Object>> checks(String text, SpeechClip clip, double match, String voiceId) {
        var a = props.audio();
        int words = TranscriptMatch.words(text).size();
        double rate = clip.durationSec() > 0 ? words / clip.durationSec() : 0;
        double maxSilence = a.maxInnerSilence().toMillis() / 1000.0;
        boolean shortLine = words <= SHORT_LINE_WORDS;
        boolean transcriptOk = match >= a.transcriptMatch()
                || (words <= 2 && clip.durationSec() <= 2.5);
        boolean rateOk = shortLine
                ? clip.durationSec() > 0.2 && clip.durationSec() <= words * 0.6 + 1.5
                : clip.durationSec() > 0.2 && rate >= a.minWordsPerSecond() && rate <= a.maxWordsPerSecond();
        return List.of(
                check("transcript_match", transcriptOk, String.format("%.0f%%", match * 100)
                        + (transcriptOk && match < a.transcriptMatch() ? " (short line, not judged by transcript)" : "")),
                check("speaking_rate", rateOk, shortLine ? String.format("short line, %.1fs", clip.durationSec())
                        : String.format("%.1f words/s", rate)),
                check("silence", clip.durationSec() > 0.2 && clip.maxSilenceSec() <= maxSilence,
                        String.format("longest gap %.1fs", clip.maxSilenceSec())),
                check("clipping", clip.clippedSamples() == 0, clip.clippedSamples() + " clipped samples"),
                check("voice", voiceId != null, "voice " + voiceId));
    }

    private static Map<String, Object> check(String name, boolean pass, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pass", pass);
        m.put("detail", detail);
        return m;
    }

    /** Joins the clips with the configured pauses, writes the track and manifest, then checks the whole track. */
    private void stitch(long storyId, int targetMin, List<Clip> clips) {
        var a = props.audio();
        List<byte[]> parts = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        Wav.Pcm first = null;
        double t = 0;
        Clip prev = null;
        for (Clip c : clips) {
            Wav.Pcm pcm = Wav.read(read(c.assetKey()));
            if (first == null) {
                first = pcm;
            } else if (pcm.sampleRate() != first.sampleRate() || pcm.channels() != first.channels()) {
                flag(storyId, "line " + c.line().id() + " has a different audio format");
                return;
            }
            if (prev != null) {
                Duration pause = !prev.scene().equals(c.scene()) ? a.pauseScene()
                        : prev.line().speaker().equals(c.line().speaker()) ? a.pauseSameSpeaker() : a.pauseSpeakerChange();
                double secs = pause.toMillis() / 1000.0;
                parts.add(Wav.silence(first.sampleRate(), first.channels(), secs));
                t += secs;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("line_id", c.line().id());
            entry.put("speaker", c.line().speaker());
            entry.put("scene", c.scene());
            entry.put("start", round(t));
            t += pcm.seconds();
            entry.put("end", round(t));
            manifest.add(entry);
            parts.add(pcm.data());
            prev = c;
        }
        String key = "stories/" + storyId + "/audio/narration.wav";
        put(key, Wav.write(first.sampleRate(), first.channels(), parts));
        String manifestJson = JSON.writeValueAsString(Map.of("lines", manifest, "duration", round(t),
                "pauses", Map.of("same_speaker", a.pauseSameSpeaker().toMillis(),
                        "speaker_change", a.pauseSpeakerChange().toMillis(), "scene", a.pauseScene().toMillis())));
        put("stories/" + storyId + "/audio/manifest.json", manifestJson.getBytes());
        boolean accepted = lines.durationAccepted(storyId);
        lines.saveTrack(storyId, key, manifestJson, t);
        if (accepted) {
            lines.acceptDuration(storyId);
        }

        double minutes = t / 60;
        if (!accepted && (minutes < a.minDurationMin() || minutes > a.maxDurationMin())) {
            flag(storyId, String.format("Narration is %.1f min, outside %.1f–%.1f min for a %d min target. "
                    + "Accept it, or send the script back to Gate B.", minutes, a.minDurationMin(), a.maxDurationMin(),
                    targetMin));
            return;
        }
        try {
            status.transition(storyId, StoryStatus.AUDIO_IN_PROGRESS, StoryStatus.AUDIO_DONE, TRIGGER,
                    String.format("%d lines, %.1f min", clips.size(), minutes));
            // The next stage (visuals, or assembly for a story short) starts automatically when audio is done.
            status.handOff(storyId, StoryStatus.AUDIO_DONE, TRIGGER, "narration ready");
        } catch (StatusConflictException e) {
            log.info("story {} moved on before audio finished: {}", storyId, e.getMessage());
        }
    }

    private <T> T withBackoff(Supplier<T> call) {
        Duration wait = props.llm().firstBackoff();
        for (int i = 0; ; i++) {
            try {
                return call.get();
            } catch (SpeechUnavailableException e) {
                if (i >= props.llm().unavailableRetries()) {
                    throw e;
                }
                try {
                    Thread.sleep(wait.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                wait = wait.multipliedBy(2);
            }
        }
    }

    private void flag(long storyId, String reason) {
        try {
            status.flag(storyId, StoryStatus.AUDIO_IN_PROGRESS, reason, TRIGGER);
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

    private static double round(double s) {
        return Math.round(s * 1000) / 1000.0;
    }
}

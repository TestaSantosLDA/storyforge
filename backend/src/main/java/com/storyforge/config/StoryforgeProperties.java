package com.storyforge.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every path, limit, threshold and model choice (CLAUDE.md "Key config defaults"). Defaults live here and in
 * application.yml; nothing in stage code is hardcoded.
 */
@ConfigurationProperties("storyforge")
public record StoryforgeProperties(
        Paths paths,
        @DefaultValue Pipeline pipeline,
        @DefaultValue Budgets budgets,
        @DefaultValue Audio audio,
        @DefaultValue Visuals visuals,
        @DefaultValue Clips clips,
        @DefaultValue Gpu gpu,
        @DefaultValue Llm llm,
        @DefaultValue Voices voices,
        @DefaultValue Script script,
        @DefaultValue Images images,
        @DefaultValue Style style,
        @DefaultValue Media media,
        @DefaultValue Brand brand,
        @DefaultValue Channel channel,
        @DefaultValue Vertical vertical,
        @DefaultValue("false") boolean madeForKids) {

    /**
     * Bound as strings: Spring's String-to-Path conversion treats a relative value like {@code ../characters} as a
     * classpath resource and rejects it.
     */
    public record Paths(String assets, String characters, String prompts, String pronunciation) {

        public Path assetsDir() {
            return Path.of(assets);
        }

        public Path charactersDir() {
            return Path.of(characters);
        }

        public Path promptsDir() {
            return Path.of(prompts);
        }

        public Path pronunciationFile() {
            return Path.of(pronunciation == null ? "../channel/pronunciation.yaml" : pronunciation);
        }
    }

    public record Pipeline(
            @DefaultValue("1") int maxConcurrentStories,
            /** Placeholder until a real budget is chosen; crossing it flags the story. */
            @DefaultValue("10.00") BigDecimal storyCostBudgetUsd) {
    }

    public record Budgets(
            @DefaultValue("5") int storyQaRetries,
            @DefaultValue("5") int scriptQaRetries,
            @DefaultValue("3") int storyRejections,
            @DefaultValue("3") int scriptRejections,
            @DefaultValue("3") int finalRejections) {
    }

    public record Audio(
            @DefaultValue("3") int retriesPerLine,
            @DefaultValue("0.95") double transcriptMatch,
            @DefaultValue("2.5") double minDurationMin,
            @DefaultValue("6.0") double maxDurationMin,
            @DefaultValue("300ms") Duration pauseSameSpeaker,
            @DefaultValue("600ms") Duration pauseSpeakerChange,
            @DefaultValue("1200ms") Duration pauseScene,
            /** Speed per attempt: Kokoro is deterministic, so a retry needs a slightly different reading. */
            @DefaultValue({"1.0", "0.95", "1.05"}) java.util.List<Double> attemptSpeeds,
            @DefaultValue("1.2") double minWordsPerSecond,
            @DefaultValue("4.5") double maxWordsPerSecond,
            @DefaultValue("1500ms") Duration maxInnerSilence) {
    }

    public record Visuals(
            @DefaultValue("5s") Duration minShot,
            @DefaultValue("10s") Duration maxShot,
            @DefaultValue("4") int maxCharactersPerShot,
            @DefaultValue("3") int imageRetries,
            @DefaultValue("4") int maxReferenceImages,
            @DefaultValue("512") int referenceMaxPx,
            /** Claude's shot plan: tries before flagging (each retry gets the failed checks). */
            @DefaultValue("3") int shotPlanRetries,
            /** The model's 16:9 render size, then upscaled to the output size when animating. */
            @DefaultValue("1360") int imageWidth,
            @DefaultValue("768") int imageHeight,
            @DefaultValue("1920") int outputWidth,
            @DefaultValue("1080") int outputHeight,
            @DefaultValue("25") int fps,
            /** How far a zoom goes over a shot (0.12 = 12 %) and how far a pan travels across the image. */
            @DefaultValue("0.12") double zoomAmount,
            @DefaultValue("0.15") double panAmount) {
    }

    public record Clips(
            @DefaultValue("3") int perStory,
            @DefaultValue("1") int minPerStory,
            @DefaultValue("20s") Duration minSlice,
            @DefaultValue("52s") Duration maxSlice,
            @DefaultValue("60s") Duration maxWithClosingLine,
            @DefaultValue("3") int rejections,
            /** Clips from any video (docs/pipeline-b/clips-from-any-video.md). */
            @DefaultValue("180") int maxSourceMinutes,
            @DefaultValue("60s") Duration minSpeech,
            @DefaultValue("700ms") Duration linePause,
            /** A stretch of speech with no sentence end or pause still becomes a line at this length. */
            @DefaultValue("12s") Duration maxLine,
            @DefaultValue("500ms") Duration sceneSnap,
            @DefaultValue("0.3") double sceneThreshold,
            @DefaultValue("3") int selectionTries,
            @DefaultValue("3") int formatTries,
            @DefaultValue("3") int downloadRetries,
            @DefaultValue("30s") Duration downloadBackoff,
            @DefaultValue("yt-dlp") String ytDlp,
            /** The version the app is pinned to; empty skips the check. */
            @DefaultValue("2026.10.26") String ytDlpVersion,
            @DefaultValue("3h") Duration transcribeTimeout,
            /** How long a job waits for the GPU, and how often it looks. */
            @DefaultValue("2h") Duration gpuWait,
            @DefaultValue("5s") Duration gpuPoll,
            @DefaultValue("1080") int width,
            @DefaultValue("1920") int height,
            @DefaultValue("3") double hookSeconds,
            @DefaultValue("-14") double loudnessTarget,
            @DefaultValue("2") double loudnessTolerance,
            /** Platform limits (B3 scenario 2: a change here needs no code). */
            @DefaultValue("100") int youtubeTitleMax,
            @DefaultValue("3") int youtubeHashtagsMax,
            @DefaultValue("2200") int tiktokCaptionMax,
            @DefaultValue("2200") int instagramCaptionMax,
            @DefaultValue("5") int otherHashtagsMax,
            @DefaultValue("256") int maxFileMb,
            /** The owner's channels; none configured means one unnamed channel with no closing line. */
            @DefaultValue({}) java.util.List<ClipChannel> channels) {

        public record ClipChannel(String name, String closingLine) {
        }
    }

    public record Gpu(@DefaultValue("15m") Duration leaseTtl) {
    }

    /** Which Claude runs the AI steps. {@code claude-code}: the local CLI on the user's plan. */
    public record Llm(
            @DefaultValue("claude-code") String engine,
            @DefaultValue("claude") String command,
            /** Empty: the CLI's default model. */
            String model,
            @DefaultValue("10m") Duration timeout,
            /** Outage / usage-limit retries before flagging; never counted as strikes. */
            @DefaultValue("3") int unavailableRetries,
            @DefaultValue("30s") Duration firstBackoff,
            /** When a usage limit is hit and the CLI gives no reset time, try again after this long. */
            @DefaultValue("1h") Duration limitRetryAfter) {
    }

    /**
     * Voices a character may be given. Kokoro English voice ids; the narrator is fixed and never assigned to a
     * character. The narrator is a placeholder until the human picks one from the Kokoro samples.
     */
    public record Voices(
            @DefaultValue("kokoro") String engine,
            @DefaultValue("bm_george") String narrator,
            @DefaultValue({"af_heart", "af_bella", "af_nicole", "af_sarah", "af_sky", "af_nova", "af_river",
                    "am_adam", "am_michael", "am_puck", "am_fenrir", "am_echo", "am_eric", "am_liam", "am_onyx",
                    "bf_emma", "bf_isabella", "bf_alice", "bf_lily", "bm_lewis", "bm_daniel", "bm_fable"})
            java.util.List<String> available) {
    }

    /** Image generation. {@code none} skips reference sheets (e.g. on a machine without the sidecar). */
    public record Images(
            @DefaultValue("sidecar") String engine,
            @DefaultValue("http://127.0.0.1:8765") String sidecarUrl,
            @DefaultValue("10m") Duration timeout,
            @DefaultValue("1024") int sheetSize,
            /** Draws per character before the best one goes to Gate A with its failed checks shown. */
            @DefaultValue("3") int sheetAttempts,
            /** How long to wait for the GPU lease before flagging. */
            @DefaultValue("30m") Duration gpuWait) {
    }

    /**
     * The channel style block added to every image prompt (docs/00-overview.md "Visual Style"). Described by its
     * ingredients only; never names a studio or franchise.
     */
    public record Style(
            @DefaultValue("Classic storybook animation still, vintage hand-drawn look. Hand-drawn characters with clean, slightly soft ink outlines and simple cel shading with one shadow tone, no gradients. Painted watercolour and gouache background, softer and less detailed than the characters. Muted palette of warm creams, sage greens, dusty blues, soft ochres and muted brick and rust reds, no saturated colours. Rounded, expressive characters with big readable faces, mid-century feature-animation tradition. Light paper grain over everything.")
            String block) {
    }

    /**
     * Channel brand assets (human setup task): intro (3-5 s) and end card (10-20 s) video files. Empty: the video is
     * assembled without them and the final review screen says so. Music bed level is relative to the narration.
     */
    public record Brand(String intro, String endCard, @DefaultValue("-18") double musicVolumeDb) {
    }

    /** Links for video descriptions: channel links, and each topic's series playlist (topic name to URL). */
    public record Channel(@DefaultValue({}) java.util.List<String> links,
            @DefaultValue({}) java.util.Map<String, String> playlists) {
    }

    /** FFmpeg, installed per machine (CLAUDE.md "Stack"). */
    /**
     * The vertical (9:16) layer shared by Pipelines C and D (docs/pipeline-d "Shared with Pipeline C"): frame size,
     * the platform safe zone, word captions and the audio mix.
     */
    public record Vertical(
            @DefaultValue("1080") int width,
            @DefaultValue("1920") int height,
            @DefaultValue("30") int fps,
            /** Shorts must stay under this. */
            @DefaultValue("60s") Duration maxDuration,
            @DefaultValue SafeZone safeZone,
            @DefaultValue Captions captions,
            @DefaultValue Mix mix) {

        /** Pixels kept clear of the platform's own buttons and text (title, channel name, like and share column). */
        public record SafeZone(
                @DefaultValue("290") int top,
                @DefaultValue("480") int bottom,
                @DefaultValue("60") int left,
                @DefaultValue("140") int right) {
        }

        public record Captions(
                /** Words on screen at once (1 to this many; a group also ends at punctuation). */
                @DefaultValue("3") int maxWords,
                @DefaultValue("Arial") String font,
                @DefaultValue("84") int fontSize,
                @DefaultValue("#FFFFFF") String colour,
                /** The word being spoken. */
                @DefaultValue("#FFD23F") String highlight,
                /** Distance from the bottom of the frame to the caption baseline; inside the safe zone. */
                @DefaultValue("560") int bottomMargin) {
        }

        public record Mix(
                /** On by default (owner, 2026-10-30); a profile or story can turn it off. */
                @DefaultValue("true") boolean music,
                /** Music level under the voice between lines, before ducking; 9 and 11 dB were too loud. */
                @DefaultValue("-17") double musicDb,
                /** Whole-mix loudness; -14, -17.5 and -23 were too loud for the owner. */
                @DefaultValue("-27") double targetLufs,
                @DefaultValue("-2") double truePeakDb,
                @DefaultValue("1.5") double musicFadeSec) {
        }
    }

    public record Media(
            @DefaultValue("ffmpeg") String ffmpeg,
            @DefaultValue("ffprobe") String ffprobe) {
    }

    /** Script length check: roughly 400–650 words for 3–5 minutes (Stage 1 script QA). */
    public record Script(
            @DefaultValue("130") int wordsPerMinute,
            @DefaultValue("0.2") double tolerance,
            @DefaultValue("400") int minWords,
            @DefaultValue("650") int maxWords) {
    }
}

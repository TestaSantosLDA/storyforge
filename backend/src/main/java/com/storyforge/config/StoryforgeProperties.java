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
        @DefaultValue("false") boolean madeForKids) {

    /**
     * Bound as strings: Spring's String-to-Path conversion treats a relative value like {@code ../characters} as a
     * classpath resource and rejects it.
     */
    public record Paths(String assets, String characters, String prompts) {

        public Path assetsDir() {
            return Path.of(assets);
        }

        public Path charactersDir() {
            return Path.of(characters);
        }

        public Path promptsDir() {
            return Path.of(prompts);
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
            @DefaultValue("1200ms") Duration pauseScene) {
    }

    public record Visuals(
            @DefaultValue("5s") Duration minShot,
            @DefaultValue("10s") Duration maxShot,
            @DefaultValue("4") int maxCharactersPerShot,
            @DefaultValue("3") int imageRetries,
            @DefaultValue("4") int maxReferenceImages,
            @DefaultValue("512") int referenceMaxPx) {
    }

    public record Clips(
            @DefaultValue("3") int perStory,
            @DefaultValue("1") int minPerStory,
            @DefaultValue("20s") Duration minSlice,
            @DefaultValue("52s") Duration maxSlice,
            @DefaultValue("60s") Duration maxWithClosingLine,
            @DefaultValue("3") int rejections) {
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

    /** Script length check: roughly 400–650 words for 3–5 minutes (Stage 1 script QA). */
    public record Script(
            @DefaultValue("130") int wordsPerMinute,
            @DefaultValue("0.2") double tolerance,
            @DefaultValue("400") int minWords,
            @DefaultValue("650") int maxWords) {
    }
}

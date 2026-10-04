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
}

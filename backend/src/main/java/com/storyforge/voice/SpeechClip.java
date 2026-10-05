package com.storyforge.voice;

/** A generated line: 16-bit mono WAV plus the numbers the per-line QA needs. */
public record SpeechClip(byte[] wav, String engineVersion, double durationSec, double peak, double maxSilenceSec,
        int clippedSamples) {
}

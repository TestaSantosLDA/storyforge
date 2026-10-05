package com.storyforge.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Test stand-in for Kokoro and Whisper. Each clip is a WAV whose length follows the word count at
 * {@code wordsPerSecond}; the transcriber "hears" the text that was spoken unless {@code mishear} changes it.
 */
public class FakeSpeech implements VoiceEngine, Transcriber {

    public record Call(String text, String voice, double speed) {
    }

    public final List<Call> calls = new ArrayList<>();
    public volatile double wordsPerSecond = 2.5;
    public volatile int failNext;
    public volatile Function<Call, String> mishear = c -> c.text();
    private final Map<byte[], Call> spoken = new IdentityHashMap<>();

    public synchronized void reset() {
        calls.clear();
        spoken.clear();
        wordsPerSecond = 2.5;
        failNext = 0;
        mishear = c -> c.text();
    }

    @Override
    public synchronized SpeechClip synthesize(String text, String voice, double speed) {
        if (failNext > 0) {
            failNext--;
            throw new SpeechUnavailableException("fake sidecar down", null);
        }
        Call call = new Call(text, voice, speed);
        calls.add(call);
        double seconds = Math.max(0.5, text.split("\s+").length / wordsPerSecond);
        byte[] wav = wav(24000, seconds);
        spoken.put(wav, call);
        return new SpeechClip(wav, "fake-tts-1", seconds, 0.5, 0.0, 0);
    }

    @Override
    public synchronized Transcript transcribe(byte[] wav) {
        Call call = spoken.get(wav);
        return new Transcript(call == null ? "" : mishear.apply(call), "fake-stt-1");
    }

    static byte[] wav(int rate, double seconds) {
        int n = (int) (rate * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + n * 2).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
                .putShort((short) 16).putInt(0x61746164).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            // A quiet tone instead of silence, so real loudness measurements have something to measure.
            b.putShort((short) (6000 * Math.sin(2 * Math.PI * 220 * i / rate)));
        }
        return b.array();
    }
}

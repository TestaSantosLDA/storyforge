package com.storyforge.voice;

/** Speech-to-text for the transcript check. MVP: Whisper (faster-whisper) in the sidecar. */
public interface Transcriber {

    Transcript transcribe(byte[] wav);

    record Transcript(String text, String engineVersion) {
    }
}

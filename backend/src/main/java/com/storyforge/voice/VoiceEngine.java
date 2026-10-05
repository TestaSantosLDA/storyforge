package com.storyforge.voice;

/** Text-to-speech behind an interface (CLAUDE.md rule 3). MVP: Kokoro in the sidecar; later: Chatterbox. */
public interface VoiceEngine {

    /** @throws SpeechUnavailableException sidecar down or model failed to load: never a line attempt */
    SpeechClip synthesize(String text, String voice, double speed);
}

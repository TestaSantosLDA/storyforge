package com.storyforge.voice;

import java.nio.file.Path;
import java.util.List;

/** Word timings for a whole media file, for clips from any video. MVP: Whisper in the sidecar. */
public interface WordTranscriber {

    Words transcribeWords(Path media);

    record Word(String text, double start, double end) {
    }

    record Words(String language, List<Word> words, String engineVersion) {
    }
}

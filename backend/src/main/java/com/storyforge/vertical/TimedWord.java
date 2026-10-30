package com.storyforge.vertical;

/** One spoken word and when it is said, in seconds from the start of the video (from Whisper word timestamps). */
public record TimedWord(String text, double start, double end) {
}

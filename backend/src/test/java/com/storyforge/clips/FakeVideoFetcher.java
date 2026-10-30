package com.storyforge.clips;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Test stand-in for yt-dlp: queued failures first, then a copy of {@code file}. */
public class FakeVideoFetcher implements VideoFetcher {

    public volatile Path file;
    public final Deque<RuntimeException> failures = new ArrayDeque<>();
    public volatile int calls;

    public synchronized void reset() {
        file = null;
        failures.clear();
        calls = 0;
    }

    @Override
    public synchronized Fetched fetch(String url, Path dir) {
        calls++;
        if (!failures.isEmpty()) {
            throw failures.poll();
        }
        try {
            return new Fetched(Files.copy(file, dir.resolve("source.mp4")), "A fetched video", "fake-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

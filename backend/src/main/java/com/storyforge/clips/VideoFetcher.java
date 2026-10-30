package com.storyforge.clips;

import java.nio.file.Path;

/** Downloads a video by link into an empty folder (CLAUDE.md rule 3). MVP: yt-dlp. */
public interface VideoFetcher {

    Fetched fetch(String url, Path dir);

    record Fetched(Path file, String title, String toolVersion) {
    }

    /** Retrying won't help (private, removed, age-restricted); the message is the tool's own. */
    class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }

    /** Possibly temporary: an outage or a site change. Backed off, never a strike (CLAUDE.md rule 6). */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }
}

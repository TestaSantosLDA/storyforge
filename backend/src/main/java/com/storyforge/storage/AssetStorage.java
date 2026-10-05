package com.storyforge.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Where generated assets live (CLAUDE.md rule 3). Keys are relative, slash-separated paths such as
 * {@code stories/42/audio/line-007-ab12cd.wav}. MVP: {@link LocalAssetStorage}; later: Cloudflare R2.
 */
public interface AssetStorage {

    /** Stores the bytes under {@code key}, replacing anything already there. Written atomically. */
    void put(String key, InputStream data) throws IOException;

    Optional<InputStream> open(String key) throws IOException;

    boolean exists(String key);

    /**
     * A local file path for tools that need one (e.g. Claude reading an image). Empty if the key doesn't exist.
     * A remote implementation would download to a temp file.
     */
    Optional<java.nio.file.Path> localPath(String key);

    /** Returns true if something was deleted. */
    boolean delete(String key) throws IOException;
}

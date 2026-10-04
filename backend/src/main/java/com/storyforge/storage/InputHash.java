package com.storyforge.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cache key for a generated asset: a SHA-256 over every input that affects it (text, voice or model, settings,
 * engine version), in order (CLAUDE.md rule 4). Same inputs, same key, so a retry finds the asset already made.
 */
public final class InputHash {

    private InputHash() {
    }

    public static String of(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) {
                    sha.update((byte) 0);
                    continue;
                }
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                // Length prefix so ("ab", "c") and ("a", "bc") differ.
                sha.update((byte) 1);
                sha.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) ':');
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

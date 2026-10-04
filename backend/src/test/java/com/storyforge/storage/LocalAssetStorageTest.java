package com.storyforge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAssetStorageTest {

    @TempDir
    Path root;

    @Test
    void put_open_exists_delete() throws Exception {
        var storage = new LocalAssetStorage(root);
        String key = "stories/42/audio/line-007.wav";

        assertThat(storage.exists(key)).isFalse();
        storage.put(key, bytes("hello"));
        assertThat(storage.exists(key)).isTrue();
        try (InputStream in = storage.open(key).orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        storage.put(key, bytes("replaced"));
        try (InputStream in = storage.open(key).orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("replaced");
        }
        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.open(key)).isEmpty();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void keys_cannot_escape_the_assets_directory() {
        var storage = new LocalAssetStorage(root);
        for (String bad : new String[] {"../secrets.txt", "/etc/passwd", "a/../../b", "C:\\x", "a//b", ""}) {
            assertThatThrownBy(() -> storage.exists(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void input_hash_is_stable_and_sensitive_to_every_part() {
        String h = InputHash.of("kokoro-1.0", "af_bella", "Once upon a time.");
        assertThat(InputHash.of("kokoro-1.0", "af_bella", "Once upon a time.")).isEqualTo(h);
        assertThat(InputHash.of("kokoro-1.1", "af_bella", "Once upon a time.")).isNotEqualTo(h);
        assertThat(InputHash.of("ab", "c")).isNotEqualTo(InputHash.of("a", "bc"));
        assertThat(InputHash.of((String) null)).isNotEqualTo(InputHash.of(""));
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}

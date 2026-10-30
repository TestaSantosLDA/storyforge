package com.storyforge.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PronunciationTest {

    @TempDir
    Path dir;

    @Test
    void entries_apply_to_whole_words_only_and_keep_the_header() throws Exception {
        Path f = dir.resolve("pronunciation.yaml");
        Files.writeString(f, "# header comment\n{}\n");
        Pronunciation p = new Pronunciation(f);
        p.put("Tamsin", "Tamzin");
        p.put("w5", "double-u five");

        assertThat(p.apply("tamsin met Tamsins at w5 now")).isEqualTo("Tamzin met Tamsins at double-u five now");
        assertThat(Files.readString(f)).startsWith("# header comment\n").contains("Tamsin");
        assertThat(new Pronunciation(f).entries()).containsEntry("w5", "double-u five");
    }
}

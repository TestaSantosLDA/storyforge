package com.storyforge.series;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.util.FileSystemUtils;

/**
 * A copy of sources/lusiadas/source.yaml with synthetic canto files that have the real stanza counts. Canto I,
 * stanza 1 is the real first stanza, so the verbatim check runs against true text.
 */
final class SourceFixture {

    static final List<String> STANZA_I_1 = List.of(
            "As armas e os barões assinalados,",
            "Que da ocidental praia Lusitana,",
            "Por mares nunca de antes navegados,",
            "Passaram ainda além da Taprobana,",
            "Em perigos e guerras esforçados,",
            "Mais do que prometia a força humana,",
            "E entre gente remota edificaram",
            "Novo Reino, que tanto sublimaram;");

    static final int[] COUNTS = {106, 113, 143, 104, 100, 99, 87, 99, 95, 156};

    private SourceFixture() {
    }

    static Path write(Path root) throws Exception {
        FileSystemUtils.deleteRecursively(root);
        Path dir = Files.createDirectories(root.resolve("lusiadas"));
        Files.copy(Path.of("../sources/lusiadas/source.yaml"), dir.resolve("source.yaml"));
        for (int c = 1; c <= COUNTS.length; c++) {
            StringBuilder sb = new StringBuilder();
            for (int s = 1; s <= COUNTS[c - 1]; s++) {
                sb.append(s).append('\n');
                List<String> lines = c == 1 && s == 1 ? STANZA_I_1
                        : java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(i -> "verso " + i).toList();
                lines.forEach(l -> sb.append(l).append('\n'));
                sb.append('\n');
            }
            Files.writeString(dir.resolve(String.format("canto-%02d.txt", c)), sb.toString());
        }
        return root;
    }
}

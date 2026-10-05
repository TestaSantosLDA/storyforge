package com.storyforge.stage2;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The channel pronunciation dictionary ({@code channel/pronunciation.yaml}, versioned in the repo). Changes only
 * what the voice engine reads; the approved script text never changes (Stage 2 decision).
 */
@Component
public class Pronunciation {

    private final Path file;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    @Autowired
    Pronunciation(StoryforgeProperties props) {
        this(props.paths().pronunciationFile());
    }

    Pronunciation(Path file) {
        this.file = file;
    }

    public synchronized Map<String, String> entries() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = yaml.readValue(file.toFile(), Map.class);
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    public String apply(String text) {
        String out = text;
        for (var e : entries().entrySet()) {
            out = Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    /** Adds or replaces one entry, keeping the file's header comment. */
    public synchronized void put(String word, String sayAs) {
        if (word == null || word.isBlank() || sayAs == null || sayAs.isBlank()) {
            throw new IllegalArgumentException("both the word and how to say it are needed");
        }
        Map<String, String> all = new LinkedHashMap<>(entries());
        all.put(word.strip(), sayAs.strip());
        try {
            String header = Files.isRegularFile(file)
                    ? Files.readAllLines(file).stream().takeWhile(l -> l.startsWith("#"))
                            .reduce("", (a, l) -> a + l + "\n")
                    : "";
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, header + yaml.writeValueAsString(all).replaceFirst("^---\s*\n", ""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.storyforge.prompt;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Prompt templates are versioned files in the repo ({@code prompts/<name>-v<N>.md}, schemas in
 * {@code prompts/schemas/}), so every output can be traced to the template that made it. Placeholders are
 * {@code {{name}}}; a missing value is an error, never silently left in the prompt.
 */
@Component
public class PromptLibrary {

    private static final Pattern VAR = Pattern.compile("\\{\\{([a-z_]+)}}");

    private final Path dir;

    @Autowired
    PromptLibrary(StoryforgeProperties props) {
        this(props.paths().promptsDir());
    }

    public PromptLibrary(Path dir) {
        this.dir = dir;
    }

    /** {@code version} is the file stem, e.g. {@code story-v1}. */
    public Prompt render(String version, Map<String, String> vars) {
        String template = read(dir.resolve(version + ".md"));
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("prompt " + version + " needs {{" + m.group(1) + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return new Prompt(version, out.toString().strip());
    }

    public String text(String version) {
        return read(dir.resolve(version + ".md")).strip();
    }

    public String schema(String version) {
        return read(dir.resolve("schemas").resolve(version + ".json"));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt file " + p.toAbsolutePath(), e);
        }
    }

    public record Prompt(String version, String text) {
    }
}

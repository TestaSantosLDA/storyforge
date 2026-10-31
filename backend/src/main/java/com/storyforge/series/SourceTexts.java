package com.storyforge.series;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Public-domain source texts, versioned in the repo under {@code sources/<id>/} (docs/series/lusiadas.md "Accuracy
 * and rights"): {@code source.yaml} with the provenance, stanza counts, suggested names and allowed translations,
 * and one {@code canto-NN.txt} per canto, each stanza as its number on a line, its verse lines, and a blank line.
 * Verse Moments quote this text verbatim; D1 receives an episode's excerpt from here.
 */
@Component
public class SourceTexts {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public record Translation(String id, String credit) {
    }

    public record Source(String id, String title, String author, int year, String language, String edition,
            String provenance, List<Integer> stanzaCounts, List<String> names, List<Translation> translations) {
    }

    public record Stanza(int canto, int number, List<String> lines) {

        public String text() {
            return String.join("\n", lines);
        }
    }

    private final Path dir;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Autowired
    SourceTexts(StoryforgeProperties props) {
        this(props.paths().sourcesDir());
    }

    public SourceTexts(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    /** The source's description, or empty if there is no {@code sources/<id>/source.yaml}. */
    public Optional<Source> find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path p = dir.resolve(id).resolve("source.yaml");
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        Source s = yaml.readValue(p.toFile(), Source.class);
        if (!id.equals(s.id())) {
            throw new IllegalStateException(p + " has id " + s.id() + ", expected " + id);
        }
        return Optional.of(s);
    }

    public Source get(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no source text sources/" + id));
    }

    /** Ids of every source with a {@code source.yaml}, for the topic page. */
    public List<String> ids() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("source.yaml"))).map(p -> p.getFileName().toString())
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether every canto file is present (the text is imported once with sources/tools/import_gutenberg.py). */
    public boolean hasText(String id) {
        Source s = get(id);
        for (int c = 1; c <= s.stanzaCounts().size(); c++) {
            if (!Files.isRegularFile(cantoFile(id, c))) {
                return false;
            }
        }
        return true;
    }

    /** One canto's stanzas in order. Throws if the file is missing or its stanzas don't match the stanza count. */
    public List<Stanza> canto(String id, int canto) {
        Source s = get(id);
        if (canto < 1 || canto > s.stanzaCounts().size()) {
            throw new IllegalArgumentException(s.title() + " has no canto " + canto);
        }
        Path p = cantoFile(id, canto);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("The text of " + s.title() + " is not in the repo yet (missing "
                    + dir.relativize(p) + "). Import it with sources/tools/import_gutenberg.py.");
        }
        List<Stanza> stanzas = parse(canto, read(p));
        int expected = s.stanzaCounts().get(canto - 1);
        for (int i = 0; i < stanzas.size(); i++) {
            if (stanzas.get(i).number() != i + 1) {
                throw new IllegalStateException(p + ": stanza " + stanzas.get(i).number() + " is out of order");
            }
        }
        if (stanzas.size() != expected) {
            throw new IllegalStateException(p + " has " + stanzas.size() + " stanzas, expected " + expected);
        }
        return stanzas;
    }

    public Stanza stanza(String id, int canto, int number) {
        List<Stanza> all = canto(id, canto);
        if (number < 1 || number > all.size()) {
            throw new IllegalArgumentException("Canto " + SourceExcerpt.roman(canto) + " has stanzas 1 to " + all.size());
        }
        return all.get(number - 1);
    }

    /** The stanzas of an episode's excerpt, verbatim, in the excerpt's order. */
    public List<Stanza> excerpt(String id, SourceExcerpt excerpt) {
        List<Stanza> out = new ArrayList<>();
        Map<Integer, List<Stanza>> cache = new LinkedHashMap<>();
        for (SourceExcerpt.Range r : excerpt.ranges()) {
            List<Stanza> c = cache.computeIfAbsent(r.canto(), n -> canto(id, n));
            out.addAll(c.subList(r.fromStanza() - 1, r.toStanza()));
        }
        return out;
    }

    /**
     * Whether a quoted text is verbatim from the stanza (scenario 4): the stanza whole, or a run of its consecutive
     * lines. Only spacing and line breaks may differ; words, accents and punctuation must match.
     */
    public boolean isVerbatim(String id, int canto, int stanza, String quoted) {
        if (quoted == null || quoted.isBlank()) {
            return false;
        }
        List<String> lines = stanza(id, canto, stanza).lines().stream().map(SourceTexts::squash).toList();
        String q = squash(quoted.replace('\n', ' '));
        for (int from = 0; from < lines.size(); from++) {
            StringBuilder run = new StringBuilder();
            for (int to = from; to < lines.size(); to++) {
                if (!run.isEmpty()) {
                    run.append(' ');
                }
                run.append(lines.get(to));
                if (run.toString().equals(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An allowed translation by id (scenario 5: only our own or pre-1929 ones, listed in source.yaml). */
    public Optional<Translation> translation(String id, String translationId) {
        return get(id).translations().stream().filter(t -> t.id().equals(translationId)).findFirst();
    }

    static List<Stanza> parse(int canto, String text) {
        List<Stanza> out = new ArrayList<>();
        Integer number = null;
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.matches("\\d+")) {
                if (number != null) {
                    out.add(new Stanza(canto, number, List.copyOf(lines)));
                }
                number = Integer.parseInt(line);
                lines = new ArrayList<>();
            } else if (!line.isEmpty() && number != null) {
                lines.add(line);
            }
        }
        if (number != null) {
            out.add(new Stanza(canto, number, List.copyOf(lines)));
        }
        return out;
    }

    private Path cantoFile(String id, int canto) {
        return dir.resolve(id).resolve(String.format("canto-%02d.txt", canto));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String squash(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }
}

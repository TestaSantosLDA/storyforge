package com.storyforge.character;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Character files are versioned in the repo, one YAML per character (CLAUDE.md "Repo hygiene"). The files are the
 * source of truth; there is no character table, so nothing is stored twice (rule 4).
 */
@Component
public class CharacterStore {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Path dir;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Autowired
    CharacterStore(StoryforgeProperties props) {
        this(props.paths().characters());
    }

    CharacterStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public List<CharacterFile> all() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().map(this::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<CharacterFile> byTopic(long topicId) {
        return all().stream().filter(c -> c.topicId() != null && c.topicId() == topicId).toList();
    }

    public Optional<CharacterFile> get(String id) {
        Path p = path(id);
        return Files.isRegularFile(p) ? Optional.of(read(p)) : Optional.empty();
    }

    /** Creates a new character file. Ids and names are unique across the channel. */
    public void create(CharacterFile c) {
        if (Files.exists(path(c.id()))) {
            throw new IllegalArgumentException("character id already exists: " + c.id());
        }
        boolean nameTaken = all().stream().anyMatch(o -> o.name().equalsIgnoreCase(c.name()));
        if (nameTaken) {
            throw new IllegalArgumentException("character name already exists: " + c.name());
        }
        write(c);
    }

    /** Replaces an existing character file, e.g. to add approved reference images. */
    public void update(CharacterFile c) {
        if (!Files.exists(path(c.id()))) {
            throw new IllegalArgumentException("no character " + c.id());
        }
        write(c);
    }

    private void write(CharacterFile c) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".char-", ".tmp");
            yaml.writeValue(tmp.toFile(), c);
            Files.move(tmp, path(c.id()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CharacterFile read(Path p) {
        return yaml.readValue(p.toFile(), CharacterFile.class);
    }

    private Path path(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad character id: " + id);
        }
        return dir.resolve(id + ".yaml");
    }
}

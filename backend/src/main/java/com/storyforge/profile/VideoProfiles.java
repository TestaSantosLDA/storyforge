package com.storyforge.profile;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.storage.InputHash;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The video profile files in {@code channel/video-profiles/} (versioned in the repo, Pipeline D "Video profiles").
 * Read on every call so an edited file applies to the next Run, never to a story already running (its snapshot).
 */
@Component
public class VideoProfiles {

    private static final Logger log = LoggerFactory.getLogger(VideoProfiles.class);

    private final Path dir;
    private final VideoProfile.Limits limits;
    private final LookNotesCheck names;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    @Autowired
    VideoProfiles(StoryforgeProperties props, LookNotesCheck names) {
        this(props.paths().profilesDir(), new VideoProfile.Limits(props.profiles().verticalMaxSeconds(),
                props.profiles().landscapeMinSeconds()), names);
    }

    VideoProfiles(Path dir, VideoProfile.Limits limits, LookNotesCheck names) {
        this.dir = dir;
        this.limits = limits;
        this.names = names;
    }

    /** A profile as read from its file, with a hash of the file so a snapshot can name the exact content. */
    public record Loaded(VideoProfile profile, Map<String, Object> raw, String fileHash) {
    }

    /** Every valid profile, by id. Invalid files are logged and left out. */
    public List<VideoProfile> all() {
        List<VideoProfile> out = new ArrayList<>();
        for (Path file : files()) {
            try {
                out.add(read(file).profile());
            } catch (ProfileException e) {
                log.warn("Video profile {} is invalid and is not offered: {}", file.getFileName(), e.getMessage());
            }
        }
        out.sort(Comparator.comparing(VideoProfile::id));
        return out;
    }

    public Optional<Loaded> find(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]*")) {
            return Optional.empty();
        }
        Path file = dir.resolve(id + ".yaml");
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    /** Applies a topic's overrides on top of a profile file and validates the result. */
    public VideoProfile merge(Loaded base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(base.raw());
        if (overrides != null) {
            merged.putAll(overrides);
        }
        VideoProfile p = VideoProfile.parse(merged, limits);
        checkNames(p);
        return p;
    }

    private Loaded read(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> raw;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = yaml.readValue(text, Map.class);
            raw = parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            throw new ProfileException("not valid YAML: " + e.getMessage());
        }
        VideoProfile p = VideoProfile.parse(raw, limits);
        String expected = file.getFileName().toString().replaceFirst("\\.yaml$", "");
        if (!expected.equals(p.id())) {
            throw new ProfileException(Map.of("id", "\"" + p.id() + "\" does not match the file name " + expected));
        }
        checkNames(p);
        return new Loaded(p, raw, InputHash.of(text));
    }

    private void checkNames(VideoProfile p) {
        Map<String, Object> fields = p.fields();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String key : VideoProfile.PROMPT_TEXT) {
            names.findBanned(String.valueOf(fields.get(key))).ifPresent(n -> errors.put(key, LookNotesCheck.refusal(n)));
        }
        if (!errors.isEmpty()) {
            throw new ProfileException(errors);
        }
    }

    private List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

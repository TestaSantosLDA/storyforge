package com.storyforge.storage;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@link AssetStorage} in a local directory outside the repo ({@code storyforge.paths.assets}). */
@Component
public class LocalAssetStorage implements AssetStorage {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    private final Path root;

    @Autowired
    LocalAssetStorage(StoryforgeProperties props) {
        this(props.paths().assetsDir());
    }

    LocalAssetStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream data) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".put-", ".tmp");
        try {
            Files.copy(data, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public Optional<InputStream> open(String key) throws IOException {
        Path p = resolve(key);
        return Files.isRegularFile(p) ? Optional.of(Files.newInputStream(p)) : Optional.empty();
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public boolean delete(String key) throws IOException {
        return Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("bad asset key: " + key);
        }
        return root.resolve(key).normalize();
    }
}

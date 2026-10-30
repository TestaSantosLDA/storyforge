package com.storyforge.animation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test stand-in for FFmpeg: writes a stub file and reports a clip of exactly the asked length. */
public class FakeAnimator implements AnimationEngine {

    public record Call(Path image, double seconds, Camera camera) {
    }

    public final List<Call> calls = new ArrayList<>();
    public volatile boolean blackFrames;

    public synchronized void reset() {
        calls.clear();
        blackFrames = false;
    }

    @Override
    public synchronized ClipInfo animate(Path image, double seconds, Camera camera, Path out) {
        calls.add(new Call(image, seconds, camera));
        try {
            Files.writeString(out, "clip");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ClipInfo(Math.round(seconds * 25) / 25.0, 1920, 1080, 25, blackFrames);
    }

    @Override
    public String version() {
        return "fake-animator-1";
    }
}

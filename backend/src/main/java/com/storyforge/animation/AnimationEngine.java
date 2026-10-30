package com.storyforge.animation;

import java.nio.file.Path;

/**
 * Turns one approved shot image into a clip exactly as long as its time range (CLAUDE.md rule 3). MVP:
 * {@link StorybookAnimator} (FFmpeg camera moves); later: Wan 2.2 image-to-video.
 */
public interface AnimationEngine {

    enum Camera { ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, HOLD }

    /** @throws AnimationException FFmpeg missing or failed: flag, not a shot attempt */
    ClipInfo animate(Path image, double seconds, Camera camera, Path out);

    String version();

    record ClipInfo(double seconds, int width, int height, double fps, boolean blackFrames) {
    }

    class AnimationException extends RuntimeException {
        public AnimationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

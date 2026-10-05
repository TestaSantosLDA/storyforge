package com.storyforge.image;

/**
 * Image generation behind an interface (CLAUDE.md rule 3). MVP: Flux 2 Klein 4B in the local Python sidecar
 * ({@link SidecarImageEngine}).
 */
public interface ImageEngine {

    /**
     * @throws ImageEngineUnavailableException sidecar down, weights missing, or out of GPU memory: not a strike
     */
    ImageResult generate(ImageRequest request);
}

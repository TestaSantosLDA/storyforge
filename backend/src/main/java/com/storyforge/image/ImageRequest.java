package com.storyforge.image;

import java.util.List;

/** One image: prompt, size, seed, and up to 4 reference images (PNG bytes), shrunk by the engine to the limit. */
public record ImageRequest(String prompt, int width, int height, long seed, List<byte[]> references,
        int referenceMaxPx) {
}

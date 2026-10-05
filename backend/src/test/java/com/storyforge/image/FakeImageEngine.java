package com.storyforge.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Test stand-in for the sidecar: returns a small PNG, records requests, can simulate an outage. */
public class FakeImageEngine implements ImageEngine {

    public final List<ImageRequest> requests = new ArrayList<>();
    public volatile int failNext;

    public synchronized void reset() {
        requests.clear();
        failNext = 0;
    }

    @Override
    public synchronized ImageResult generate(ImageRequest request) {
        if (failNext > 0) {
            failNext--;
            throw new ImageEngineUnavailableException("fake sidecar down", null);
        }
        requests.add(request);
        try {
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return new ImageResult(out.toByteArray(), "fake-image-1", 0.1, 1.0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

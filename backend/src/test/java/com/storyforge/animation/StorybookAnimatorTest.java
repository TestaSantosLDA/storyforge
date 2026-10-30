package com.storyforge.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.storyforge.config.StoryforgeProperties;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Runs the real FFmpeg when it is installed (Stage 3 clip QA: exact duration, 1920x1080, no black frames). */
class StorybookAnimatorTest {

    @TempDir
    Path dir;

    static StorybookAnimator animator() {
        Map<String, String> m = new HashMap<>(Map.of("storyforge.paths.assets", "x", "storyforge.paths.characters", "x",
                "storyforge.paths.prompts", "x"));
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        if (bin != null) {
            m.put("storyforge.media.ffmpeg", bin + "/ffmpeg");
            m.put("storyforge.media.ffprobe", bin + "/ffprobe");
        }
        var props = new Binder(new MapConfigurationPropertySource(m)).bind("storyforge", StoryforgeProperties.class).get();
        return new StorybookAnimator(props);
    }

    static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder(System.getenv().getOrDefault("STORYFORGE_FFMPEG_BIN", "") .isEmpty() ? "ffmpeg"
                    : System.getenv("STORYFORGE_FFMPEG_BIN") + "/ffmpeg", "-version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    Path picture() throws Exception {
        BufferedImage img = new BufferedImage(1360, 768, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(240, 230, 210), 1360, 768, new Color(120, 150, 130)));
        g.fillRect(0, 0, 1360, 768);
        g.dispose();
        Path p = dir.resolve("shot.png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @ParameterizedTest
    @EnumSource(AnimationEngine.Camera.class)
    void every_camera_move_renders_an_exact_length_1080p_clip(AnimationEngine.Camera camera) throws Exception {
        assumeTrue(ffmpegAvailable(), "FFmpeg not on PATH; set STORYFORGE_FFMPEG_BIN to run this test");
        Path out = dir.resolve(camera + ".mp4");

        var info = animator().animate(picture(), 5.48, camera, out);

        assertThat(Files.size(out)).isPositive();
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.fps()).isEqualTo(25.0);
        assertThat(Math.abs(info.seconds() - 5.48)).isLessThanOrEqualTo(1.0 / 25 + 1e-6);
        assertThat(info.blackFrames()).isFalse();
    }

    @Test
    void zoom_and_pan_expressions_stay_inside_the_image() {
        StorybookAnimator a = animator();
        assertThat(a.move(AnimationEngine.Camera.ZOOM_IN, 100)).contains("z='1+0.1200*on/99'");
        assertThat(a.move(AnimationEngine.Camera.PAN_RIGHT, 100)).contains("x='(iw-iw/zoom)*on/99'");
        assertThat(a.move(AnimationEngine.Camera.PAN_LEFT, 100)).contains("x='(iw-iw/zoom)*(1-on/99)'");
    }
}

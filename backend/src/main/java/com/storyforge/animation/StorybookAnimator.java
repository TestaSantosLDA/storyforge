package com.storyforge.animation;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storybook animation with FFmpeg: the image is upscaled, then {@code zoompan} moves a 16:9 window across it for
 * exactly the shot's frame count. The window always stays inside the image, so there are never empty edges
 * (Stage 3 clip QA "Motion"). The image is first scaled to 4x the output to keep the slow move free of jitter.
 */
public class StorybookAnimator implements AnimationEngine {

    private final StoryforgeProperties.Visuals v;
    private final StoryforgeProperties.Media media;

    public StorybookAnimator(StoryforgeProperties props) {
        this.v = props.visuals();
        this.media = props.media();
    }

    @Override
    public String version() {
        return "storybook-ffmpeg-1";
    }

    @Override
    public ClipInfo animate(Path image, double seconds, Camera camera, Path out) {
        int frames = Math.max(1, (int) Math.round(seconds * v.fps()));
        int w = v.outputWidth();
        int h = v.outputHeight();
        String filter = String.format(Locale.ROOT,
                "[0:v]scale=%d:%d,zoompan=%s:d=%d:s=%dx%d:fps=%d,format=yuv420p",
                w * 4, h * 4, move(camera, frames), frames, w, h, v.fps());
        run(List.of(media.ffmpeg(), "-y", "-loglevel", "error", "-loop", "1", "-i", image.toString(),
                "-filter_complex", filter, "-frames:v", String.valueOf(frames), "-c:v", "libx264", "-preset", "medium",
                "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()), 600);
        return probe(out);
    }

    /** zoompan expressions; {@code on} is the output frame number. Pans run with a fixed zoom so there's room to move. */
    String move(Camera camera, int frames) {
        int last = Math.max(1, frames - 1);
        double z = v.zoomAmount();
        double p = v.panAmount();
        String centre = "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'";
        return switch (camera) {
            case ZOOM_IN -> String.format(Locale.ROOT, "z='1+%.4f*on/%d':%s", z, last, centre);
            case ZOOM_OUT -> String.format(Locale.ROOT, "z='%.4f-%.4f*on/%d':%s", 1 + z, z, last, centre);
            case PAN_LEFT -> String.format(Locale.ROOT,
                    "z='%.4f':x='(iw-iw/zoom)*(1-on/%d)':y='ih/2-(ih/zoom/2)'", 1 / (1 - p), last);
            case PAN_RIGHT -> String.format(Locale.ROOT,
                    "z='%.4f':x='(iw-iw/zoom)*on/%d':y='ih/2-(ih/zoom/2)'", 1 / (1 - p), last);
            case HOLD -> "z='1':" + centre;
        };
    }

    /** Duration, size, frame rate and whether any black frames were found. */
    public ClipInfo probe(Path clip) {
        String info = run(List.of(media.ffprobe(), "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height,r_frame_rate:format=duration", "-of", "default=noprint_wrappers=1", clip.toString()),
                60);
        int width = (int) num(info, "width");
        int height = (int) num(info, "height");
        double duration = num(info, "duration");
        Matcher r = Pattern.compile("r_frame_rate=(\\d+)/(\\d+)").matcher(info);
        double fps = r.find() ? Double.parseDouble(r.group(1)) / Double.parseDouble(r.group(2)) : 0;
        String black = run(List.of(media.ffmpeg(), "-v", "info", "-i", clip.toString(), "-vf",
                "blackdetect=d=0.1:pix_th=0.05", "-an", "-f", "null", "-"), 300);
        return new ClipInfo(duration, width, height, fps, black.contains("black_start"));
    }

    private static double num(String text, String key) {
        Matcher m = Pattern.compile(key + "=([0-9.]+)").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static String run(List<String> cmd, int timeoutSeconds) {
        try {
            Process p = new ProcessBuilder(new ArrayList<>(cmd)).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AnimationException(cmd.get(0) + " timed out", null);
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new AnimationException(cmd.get(0) + " failed: " + text.substring(Math.max(0, text.length() - 400)),
                        null);
            }
            return text;
        } catch (IOException e) {
            throw new AnimationException("could not run " + cmd.get(0) + " (is FFmpeg installed?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnimationException("interrupted", e);
        }
    }
}

package com.storyforge.clips;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** yt-dlp (Unlicense), best quality up to 1080p. The version in use is compared to the pinned one and logged. */
@Component
@ConditionalOnProperty(name = "storyforge.clips.fetcher", havingValue = "yt-dlp", matchIfMissing = true)
class YtDlpFetcher implements VideoFetcher {

    private static final Logger log = LoggerFactory.getLogger(YtDlpFetcher.class);
    private static final Pattern REFUSED = Pattern.compile("(?i)private video|sign in|confirm your age|age-restricted"
            + "|members-only|video unavailable|has been removed|account .* terminated|is not available");
    private static final int TIMEOUT_SECONDS = 4 * 3600;

    private final StoryforgeProperties props;

    YtDlpFetcher(StoryforgeProperties props) {
        this.props = props;
    }

    @Override
    public Fetched fetch(String url, Path dir) {
        var c = props.clips();
        String version = run(List.of(c.ytDlp(), "--version"), 60).strip();
        if (!c.ytDlpVersion().isBlank() && !c.ytDlpVersion().equals(version)) {
            log.warn("yt-dlp is {} but {} is pinned", version, c.ytDlpVersion());
        }
        List<String> cmd = new ArrayList<>(List.of(c.ytDlp(), "--no-playlist", "--no-warnings", "--quiet",
                "--no-simulate", "--print", "title", "--ffmpeg-location", props.media().ffmpeg(),
                "-f", "bv*[height<=1080]+ba/b[height<=1080]/b", "--merge-output-format", "mp4",
                "-o", dir.resolve("source.%(ext)s").toString(), "--", url));
        String out = run(cmd, TIMEOUT_SECONDS);
        String title = out.lines().filter(l -> !l.isBlank()).findFirst().orElse("YouTube video").strip();
        try (var files = Files.list(dir)) {
            Path file = files.filter(f -> f.getFileName().toString().startsWith("source.")).findFirst()
                    .orElseThrow(() -> new UnavailableException("yt-dlp finished but left no file"));
            return new Fetched(file, title, version);
        } catch (IOException e) {
            throw new UnavailableException("could not read the download folder: " + e.getMessage());
        }
    }

    private static String run(List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new UnavailableException("yt-dlp timed out");
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                String tail = text.substring(Math.max(0, text.length() - 600)).strip();
                if (REFUSED.matcher(tail).find()) {
                    throw new RefusedException(tail);
                }
                throw new UnavailableException(tail);
            }
            return text;
        } catch (IOException e) {
            throw new UnavailableException("could not run yt-dlp (is it installed?): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("interrupted");
        }
    }
}

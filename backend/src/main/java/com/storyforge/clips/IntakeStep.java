package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Job;
import com.storyforge.clips.ClipJobs.Line;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.gpu.GpuLease;
import com.storyforge.stage4.Assembler;
import com.storyforge.storage.AssetStorage;
import com.storyforge.story.StatusConflictException;
import com.storyforge.voice.SpeechUnavailableException;
import com.storyforge.voice.WordTranscriber;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Intake: fetches and validates the video ({@code ingesting}), then transcribes it and finds its scene cuts
 * ({@code transcribing}). A video already seen (by content hash) keeps its transcript.
 */
@Component
public class IntakeStep {

    static final String TRIGGER = "system:clip-intake";
    private static final Logger log = LoggerFactory.getLogger(IntakeStep.class);

    private final ClipJobs jobs;
    private final ObjectProvider<VideoFetcher> fetcher;
    private final WordTranscriber transcriber;
    private final AssetStorage assets;
    private final GpuLease gpu;
    private final JdbcClient jdbc;
    private final StoryforgeProperties props;
    private final Assembler media;

    IntakeStep(ClipJobs jobs, ObjectProvider<VideoFetcher> fetcher, WordTranscriber transcriber, AssetStorage assets,
            GpuLease gpu, JdbcClient jdbc, StoryforgeProperties props) {
        this.jobs = jobs;
        this.fetcher = fetcher;
        this.transcriber = transcriber;
        this.assets = assets;
        this.gpu = gpu;
        this.jdbc = jdbc;
        this.props = props;
        this.media = new Assembler(props);
    }

    private record Fetched(Path file, String title, String origin, String ytDlpVersion) {
    }

    public void ingest(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.INGESTING) {
            return;
        }
        if (job.sourceVideoId() != null) {
            moveOn(job, jobs.source(job.sourceVideoId()).orElseThrow()); // restarted after the link
            return;
        }
        Path tmp = props.paths().assetsDir().resolve("clips/tmp/job-" + jobId).toAbsolutePath();
        try {
            deleteAll(tmp); // a partial download from before a restart is never reused
            Files.createDirectories(tmp);
            if (job.url() != null) {
                var known = jobs.sourceByUrl(job.url());
                if (known.isPresent()) {
                    link(job, known.get());
                    return;
                }
            }
            Fetched f = job.url() != null ? download(job, tmp) : upload(job);
            if (f == null) {
                return; // flagged
            }
            String hash = sha256(f.file());
            var same = jobs.sourceByHash(hash);
            if (same.isPresent()) {
                link(job, same.get());
                return;
            }
            Assembler.Probe p;
            try {
                p = media.probe(f.file());
            } catch (Assembler.AssemblyException e) {
                jobs.flag(jobId, ClipStatus.INGESTING, "That file couldn't be read as a video.", TRIGGER);
                return;
            }
            if (p.videoCodec() == null || p.audioCodec() == null) {
                jobs.flag(jobId, ClipStatus.INGESTING, "The file needs both a video and an audio track.", TRIGGER);
                return;
            }
            int limit = props.clips().maxSourceMinutes();
            if (p.seconds() > limit * 60.0) {
                jobs.flag(jobId, ClipStatus.INGESTING, String.format(Locale.ROOT,
                        "The video is %.0f minutes long; the limit is %d.", p.seconds() / 60, limit), TRIGGER);
                return;
            }
            String key = "clips/sources/" + hash + ext(f.file().getFileName().toString());
            try (InputStream in = Files.newInputStream(f.file())) {
                assets.put(key, in);
            }
            long sourceId = jobs.addSource(f.origin(), job.url(), key, hash, f.title(), p.seconds(), p.width(),
                    p.height(), f.ytDlpVersion());
            link(job, jobs.source(sourceId).orElseThrow());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during intake: {}", jobId, e.getMessage());
        } finally {
            deleteAll(tmp);
        }
    }

    private Fetched upload(Job job) throws IOException {
        Path path = assets.localPath(job.uploadKey()).orElse(null);
        if (path == null) {
            jobs.flag(job.id(), ClipStatus.INGESTING, "The uploaded file is missing; upload it again.", TRIGGER);
            return null;
        }
        String name = job.uploadName() == null ? "Uploaded video" : job.uploadName().replaceAll("\\.[^.]*$", "");
        return new Fetched(path, name, "upload", null);
    }

    /** An outage is backed off and never a strike (CLAUDE.md rule 6); a refusal won't change, so it flags at once. */
    private Fetched download(Job job, Path dir) throws IOException {
        Duration wait = props.clips().downloadBackoff();
        for (int attempt = 0; ; attempt++) {
            try {
                var f = fetcher.getObject().fetch(job.url(), dir);
                return new Fetched(f.file(), f.title(), "youtube", f.toolVersion());
            } catch (VideoFetcher.RefusedException e) {
                jobs.flag(job.id(), ClipStatus.INGESTING, "Download refused: " + e.getMessage()
                        + " Upload the file instead.", TRIGGER);
                return null;
            } catch (VideoFetcher.UnavailableException e) {
                log.warn("clip job {} download failed: {}", job.id(), e.getMessage());
                if (attempt >= props.clips().downloadRetries()) {
                    jobs.flag(job.id(), ClipStatus.INGESTING, "Download failed: " + e.getMessage()
                            + " Update yt-dlp or upload the file.", TRIGGER);
                    return null;
                }
                sleep(wait);
                wait = wait.multipliedBy(2);
                deleteAll(dir);
                Files.createDirectories(dir);
            }
        }
    }

    private void link(Job job, Source source) throws IOException {
        jobs.linkSource(job.id(), source.id());
        if (job.uploadKey() != null) {
            assets.delete(job.uploadKey());
        }
        moveOn(job, source);
    }

    private void moveOn(Job job, Source source) {
        boolean transcribed = source.lines() != null;
        jobs.transition(job.id(), ClipStatus.INGESTING, transcribed ? ClipStatus.SELECTING : ClipStatus.TRANSCRIBING,
                TRIGGER, transcribed ? "same video as an earlier job; transcript reused" : "file fetched and valid");
    }

    public void transcribe(long jobId) {
        Job job = jobs.get(jobId);
        if (job.status() != ClipStatus.TRANSCRIBING) {
            return;
        }
        Source source = jobs.source(job.sourceVideoId()).orElseThrow();
        try {
            if (source.lines() == null) {
                Path file = assets.localPath(source.fileKey()).orElseThrow();
                var words = withGpu(jobId, () -> transcriber.transcribeWords(file));
                var c = props.clips();
                List<Line> lines = Transcript.lines(words.words(), seconds(c.linePause()), seconds(c.maxLine()));
                jobs.saveTranscript(source.id(), words.language(), lines, words.words(), sceneCuts(file),
                        words.engineVersion());
                source = jobs.source(source.id()).orElseThrow();
            }
            double speech = Transcript.speech(source.lines());
            double need = seconds(props.clips().minSpeech());
            if (speech < need) {
                jobs.flag(jobId, ClipStatus.TRANSCRIBING, String.format(Locale.ROOT,
                        "Only %.0f s of speech found; %.0f s are needed. You can still pick slices by hand.", speech,
                        need), TRIGGER);
                return;
            }
            if (!Transcript.ordered(source.lines())) {
                jobs.flag(jobId, ClipStatus.TRANSCRIBING, "The transcript's lines overlap or are out of order.", TRIGGER);
                return;
            }
            jobs.transition(jobId, ClipStatus.TRANSCRIBING, ClipStatus.SELECTING, TRIGGER,
                    source.lines().size() + " lines, language " + source.language());
        } catch (SpeechUnavailableException | GpuBusyException e) {
            flag(jobId, e.getMessage());
        } catch (StatusConflictException e) {
            log.info("clip job {} moved on during transcription: {}", jobId, e.getMessage());
        }
    }

    private static final class GpuBusyException extends RuntimeException {
        GpuBusyException(String message) {
            super(message);
        }
    }

    /**
     * Waits for the GPU behind Pipeline A, then keeps the lease alive for as long as the work takes (a long video
     * outlasts one lease). The wait keeps the job in {@code transcribing} (scenario 10).
     */
    private <T> T withGpu(long jobId, java.util.function.Supplier<T> work) {
        String holder = "clip-job-" + jobId;
        Instant deadline = Instant.now().plus(props.clips().gpuWait());
        while (pipelineAWantsGpu() || !gpu.tryAcquire(holder)) {
            if (Instant.now().isAfter(deadline)) {
                throw new GpuBusyException("GPU busy for longer than " + props.clips().gpuWait());
            }
            sleep(props.clips().gpuPoll());
        }
        ScheduledExecutorService renew = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clip-gpu-renew");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1, props.gpu().leaseTtl().toSeconds() / 3);
        renew.scheduleAtFixedRate(() -> gpu.tryAcquire(holder), every, every, TimeUnit.SECONDS);
        try {
            return work.get();
        } finally {
            renew.shutdownNow();
            gpu.release(holder);
        }
    }

    private boolean pipelineAWantsGpu() {
        return jdbc.sql("select count(*) from story where status in ('audio_in_progress', 'visuals_in_progress')")
                .query(Long.class).single() > 0;
    }

    /** Scene changes in the source, from a small copy of the picture. */
    List<Double> sceneCuts(Path file) {
        String filter = String.format(Locale.ROOT, "scale=320:-2,select='gt(scene,%.2f)',showinfo",
                props.clips().sceneThreshold());
        String out = Assembler.run(List.of(props.media().ffmpeg(), "-hide_banner", "-i", file.toString(), "-an",
                "-vf", filter, "-f", "null", "-"), 4 * 3600);
        List<Double> cuts = new ArrayList<>();
        Matcher m = Pattern.compile("pts_time:([0-9.]+)").matcher(out);
        while (m.find()) {
            cuts.add(Double.parseDouble(m.group(1)));
        }
        return cuts;
    }

    private void flag(long jobId, String reason) {
        try {
            jobs.flag(jobId, ClipStatus.TRANSCRIBING, reason, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("clip job {} not flagged: {}", jobId, e.getMessage());
        }
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        String e = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        return e.matches("\\.[a-z0-9]{2,5}") ? e : ".mp4";
    }

    private static String sha256(Path file) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("could not clear {}: {}", dir, e.getMessage());
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoFetcher.UnavailableException("interrupted");
        }
    }
}

package com.storyforge.clips;

import static com.storyforge.clips.ClipStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.storyforge.DbTest;
import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.gpu.GpuLease;
import com.storyforge.llm.FakeLlmEngine;
import com.storyforge.stage1.Stage1Fixtures;
import com.storyforge.stage4.Assembler;
import com.storyforge.storage.AssetStorage;
import com.storyforge.validation.InvalidInputException;
import com.storyforge.voice.FakeSpeech;
import com.storyforge.voice.WordTranscriber.Word;
import com.storyforge.voice.WordTranscriber.Words;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

/**
 * The "Clips from any video" scenarios table with real FFmpeg on a small generated video (fake Claude, Whisper and
 * yt-dlp). Skipped when FFmpeg isn't available; set STORYFORGE_FFMPEG_BIN to its folder if it isn't on PATH.
 */
@TestPropertySource(properties = {"storyforge.clips.download-backoff=1ms", "storyforge.clips.gpu-poll=20ms"})
class ClipsScenariosTest extends DbTest {

    static final String BY = "user:tester";
    static final String LINK = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final Path VIDEO = Path.of("target/test-clips/source.mp4").toAbsolutePath();
    static final Path OTHER = Path.of("target/test-clips/other.mp4").toAbsolutePath();
    static boolean ffmpegReady;

    @DynamicPropertySource
    static void ffmpeg(DynamicPropertyRegistry r) {
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        if (bin != null) {
            r.add("storyforge.media.ffmpeg", () -> bin + "/ffmpeg");
            r.add("storyforge.media.ffprobe", () -> bin + "/ffprobe");
        }
    }

    @BeforeAll
    static void makeVideos() throws Exception {
        Files.createDirectories(VIDEO.getParent());
        String bin = System.getenv("STORYFORGE_FFMPEG_BIN");
        String ffmpeg = bin == null ? "ffmpeg" : bin + "/ffmpeg";
        ffmpegReady = make(ffmpeg, VIDEO, "320x180", 440, 150) && make(ffmpeg, OTHER, "180x320", 330, 90);
    }

    private static boolean make(String ffmpeg, Path out, String size, int tone, int seconds) throws Exception {
        if (Files.exists(out)) {
            return true;
        }
        try {
            return new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                    "testsrc=size=" + size + ":rate=25:duration=" + seconds, "-f", "lavfi", "-i",
                    "sine=frequency=" + tone + ":sample_rate=48000:duration=" + seconds, "-c:v", "libx264", "-preset",
                    "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    .waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    @Autowired FakeLlmEngine claude;
    @Autowired FakeSpeech speech;
    @Autowired FakeVideoFetcher fetcher;
    @Autowired ClipJobs jobs;
    @Autowired IntakeStep intake;
    @Autowired SelectStep select;
    @Autowired RenderStep render;
    @Autowired FormatStep format;
    @Autowired ClipReview review;
    @Autowired AssetStorage assets;
    @Autowired GpuLease gpu;
    @Autowired StoryforgeProperties props;

    /** Three 25 s stretches of speech, one sentence every 5 s. */
    static Words words() {
        List<Word> w = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            double t = i * 5 + 1;
            w.add(new Word("Word" + i, t, t + 1));
            w.add(new Word("again.", t + 1, t + 3.9));
        }
        return new Words("en", w, "fake-stt-1");
    }

    static String picks() {
        return """
                {"clips":[{"start_line":"L0001","end_line":"L0005","hook_title":"First"},
                          {"start_line":"L0006","end_line":"L0010","hook_title":"Second"},
                          {"start_line":"L0011","end_line":"L0015","hook_title":"Third"}]}
                """;
    }

    static final String POSTS = """
            {"youtube":{"caption":"A title","hashtags":["#a"]},"tiktok":{"caption":"A caption","hashtags":["#a"]},
             "instagram":{"caption":"A caption","hashtags":["#a"]},"cover_second":2}
            """;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(ffmpegReady, "FFmpeg not available");
        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        claude.reset();
        speech.reset();
        fetcher.reset();
        speech.words = words();
        fetcher.file = VIDEO;
        claude.byDefault("clip-select", picks())
                .byDefault("clip-select-qa", Stage1Fixtures.qaPass("opening"))
                .byDefault("clip-format", POSTS)
                .byDefault("clip-format-qa", Stage1Fixtures.qaPass("honesty"))
                .byDefault("clip-rejection", "{\"target\":\"format\",\"notes\":\"Warmer caption.\",\"hook_title\":\"\"}");
    }

    private ClipJobs.Form link(String url) {
        return new ClipJobs.Form(url, null, null, "my own video", true, "Main channel", 3, null, false, null);
    }

    private long upload(Path file) throws IOException {
        String key = "clips/incoming/up-" + System.nanoTime() + ".mp4";
        assets.put(key, new ByteArrayInputStream(Files.readAllBytes(file)));
        return jobs.submit(new ClipJobs.Form(null, key, "my talk.mp4", "my own video", true, "Main channel", 3, null,
                false, null), BY);
    }

    private ClipStatus status(long id) {
        return jobs.get(id).status();
    }

    private void toSelected(long id) {
        intake.ingest(id);
        intake.transcribe(id);
    }

    private void toReady(long id) {
        toSelected(id);
        select.run(id);
        render.run(id);
        format.run(id);
    }

    @Test
    void scenario1And2_linkAndUploadBothReachClipReady() throws Exception {
        long a = upload(VIDEO);
        toReady(a);
        assertThat(status(a)).isEqualTo(CLIP_READY);
        List<Clip> clips = jobs.clips(a);
        assertThat(clips).hasSize(3).allMatch(c -> c.status() == CLIP_READY);
        Assembler.Probe p = new Assembler(props).probe(assets.localPath(clips.getFirst().videoKey()).orElseThrow());
        assertThat(p.width()).isEqualTo(1080);
        assertThat(p.height()).isEqualTo(1920);
        assertThat(jobs.variants(clips.getFirst().id())).hasSize(3)
                .allMatch(v -> v.cta().equals(FormatStep.OWN_CTA) && assets.exists(v.coverKey()));

        FileSystemUtils.deleteRecursively(Path.of("target/test-assets"));
        long b = jobs.submit(link("https://youtu.be/abcdefghijk"), BY);
        fetcher.file = OTHER; // a different file, so nothing is reused
        speech.words = words();
        toReady(b);
        assertThat(status(b)).isEqualTo(CLIP_READY);
        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void scenario3And4_badFormsAreRefused() {
        assertThatThrownBy(() -> jobs.submit(new ClipJobs.Form(LINK, null, null, " ", true, "Main channel", 3, null,
                false, null), BY)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> jobs.submit(new ClipJobs.Form(null, null, null, "mine", true, "Main channel", 3, null,
                false, null), BY)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> jobs.submit(new ClipJobs.Form(LINK, "clips/incoming/x.mp4", "x.mp4", "mine", true,
                "Main channel", 3, null, false, null), BY)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> jobs.submit(link("https://example.com/video"), BY))
                .isInstanceOf(InvalidInputException.class);
        assertThat(jobs.all()).isEmpty();
    }

    @Test
    void scenario5_privateVideoFlagsAtOnceAndSuggestsUploading() throws Exception {
        fetcher.failures.add(new VideoFetcher.RefusedException("ERROR: Private video."));
        long id = jobs.submit(link(LINK), BY);
        intake.ingest(id);
        assertThat(status(id)).isEqualTo(NEEDS_ATTENTION);
        assertThat(jobs.get(id).attentionReason()).contains("Private video").contains("Upload the file");
        assertThat(fetcher.calls).isEqualTo(1);

        String key = "clips/incoming/new.mp4";
        assets.put(key, Files.newInputStream(VIDEO));
        jobs.replaceFile(id, key, "new.mp4", BY);
        assertThat(status(id)).isEqualTo(INGESTING);
        intake.ingest(id);
        assertThat(status(id)).isEqualTo(TRANSCRIBING);
    }

    @Test
    void scenario6_brokenYtDlpBacksOffThenFlags() {
        for (int i = 0; i < 4; i++) {
            fetcher.failures.add(new VideoFetcher.UnavailableException("ERROR: unable to extract"));
        }
        long id = jobs.submit(link(LINK), BY);
        intake.ingest(id);
        assertThat(fetcher.calls).isEqualTo(4);
        assertThat(status(id)).isEqualTo(NEEDS_ATTENTION);
        assertThat(jobs.get(id).attentionReason()).contains("Update yt-dlp");
    }

    @Test
    void scenario8_tooLittleSpeechFlagsButSlicesCanBePickedByHand() throws Exception {
        speech.words = new Words("en", words().words().subList(0, 12), "fake-stt-1");
        long id = upload(VIDEO);
        toSelected(id);
        assertThat(status(id)).isEqualTo(NEEDS_ATTENTION);
        assertThat(jobs.get(id).attentionReason()).contains("speech");

        assertThatThrownBy(() -> review.manualSlice(id, "L0001", "L0002", "Too short", BY))
                .isInstanceOf(InvalidInputException.class);
        review.manualSlice(id, "L0001", "L0005", "By hand", BY);
        assertThat(status(id)).isEqualTo(RENDERING);
        assertThat(jobs.clips(id)).hasSize(1);
    }

    @Test
    void scenario9_sameVideoTwiceReusesTheIntake() throws Exception {
        long first = upload(VIDEO);
        toSelected(first);
        long second = upload(VIDEO);
        intake.ingest(second);
        assertThat(status(second)).isEqualTo(SELECTING);
        assertThat(jobs.get(second).sourceVideoId()).isEqualTo(jobs.get(first).sourceVideoId());
        assertThat(speech.wordCalls).isEqualTo(1);
    }

    @Test
    void scenario10_whisperWaitsForTheGpu() throws Exception {
        long id = upload(VIDEO);
        intake.ingest(id);
        assertThat(gpu.tryAcquire("pipeline-a")).isTrue();
        var waiting = CompletableFuture.runAsync(() -> intake.transcribe(id));
        Thread.sleep(300);
        assertThat(status(id)).isEqualTo(TRANSCRIBING);
        assertThat(speech.wordCalls).isZero();
        gpu.release("pipeline-a");
        waiting.get(30, TimeUnit.SECONDS);
        assertThat(speech.wordCalls).isEqualTo(1);
        assertThat(status(id)).isEqualTo(SELECTING);
    }

    @Test
    void scenario11_cropThatCutsOffTheSpeakerRendersAgainWithNewFraming() throws Exception {
        long id = upload(VIDEO);
        toReady(id);
        Clip clip = jobs.clips(id).getFirst();
        review.reject(clip.id(), "speaker cut off", "crop_offset", 0.2, BY);
        assertThat(status(id)).isEqualTo(RENDERING);
        assertThat(jobs.clip(clip.id()).status()).isEqualTo(RENDERING);
        assertThat(jobs.clip(clip.id()).cropOffset()).isEqualTo(0.2);
        render.run(id);
        format.run(id);
        Clip redone = jobs.clip(clip.id());
        assertThat(redone.status()).isEqualTo(CLIP_READY);
        assertThat(redone.videoKey()).isNotEqualTo(clip.videoKey());
        assertThat(jobs.clip(jobs.clips(id).get(1).id()).videoKey()).isEqualTo(jobs.clips(id).get(1).videoKey());
    }

    @Test
    void scenario12_nonEnglishPostTextAsksForTheVideosLanguage() throws Exception {
        speech.words = new Words("pt", words().words(), "fake-stt-1");
        long id = upload(VIDEO);
        toReady(id);
        assertThat(status(id)).isEqualTo(CLIP_READY);
        assertThat(claude.requests("clip-format").getFirst().userPrompt()).contains("code: pt");
    }

    @Test
    void scenario13_restartMidDownloadDiscardsThePartialFile() throws Exception {
        long id = jobs.submit(link(LINK), BY);
        Path tmp = props.paths().assetsDir().resolve("clips/tmp/job-" + id);
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("source.mp4.part"), "half a file");
        intake.ingest(id);
        assertThat(Files.exists(tmp)).isFalse();
        assertThat(status(id)).isEqualTo(TRANSCRIBING);
    }

    @Test
    void thirdPartySourceGetsACreditInsteadOfTheChannelCallToAction() throws Exception {
        String key = "clips/incoming/third.mp4";
        assets.put(key, Files.newInputStream(VIDEO));
        long id = jobs.submit(new ClipJobs.Form(null, key, "stream.mp4", "streamer X, clipping program", false,
                "Main channel", 1, null, false, null), BY);
        claude.then("clip-select", "{\"clips\":[{\"start_line\":\"L0001\",\"end_line\":\"L0005\",\"hook_title\":\"One\"}]}");
        toReady(id);
        assertThat(jobs.variants(jobs.clips(id).getFirst().id())).allMatch(v -> v.cta().startsWith("Clip from stream"));
    }

    @Test
    void selectionRejectsOverlongSlicesThenFlagsAfterThreeTries() throws Exception {
        String overlong = "{\"clips\":[{\"start_line\":\"L0001\",\"end_line\":\"L0020\",\"hook_title\":\"Too long\"}]}";
        claude.byDefault("clip-select", overlong);
        long id = upload(VIDEO);
        toSelected(id);
        select.run(id);
        assertThat(status(id)).isEqualTo(NEEDS_ATTENTION);
        assertThat(claude.requests("clip-select")).hasSize(3);
        assertThat(jobs.get(id).attentionReason()).contains("pick the lines by hand");
    }

    @Test
    void rejectionIsRoutedByClaudeAndTheThirdKillsTheClip() throws Exception {
        long id = upload(VIDEO);
        toReady(id);
        Clip clip = jobs.clips(id).getFirst();
        review.reject(clip.id(), "the caption is dull", null, null, BY);
        assertThat(status(id)).isEqualTo(ANALYZING_REJECTION);
        review.analyze(id);
        assertThat(status(id)).isEqualTo(FORMATTING);
        format.run(id);
        assertThat(status(id)).isEqualTo(CLIP_READY);
        assertThat(claude.requests("clip-format").getLast().userPrompt()).contains("Warmer caption.");

        review.reject(clip.id(), "still dull", null, null, BY);
        review.analyze(id);
        format.run(id);
        review.reject(clip.id(), "no", null, null, BY);
        assertThat(jobs.clip(clip.id()).status()).isEqualTo(CLIP_KILLED);
        assertThat(status(id)).isEqualTo(CLIP_READY);
    }

    @Test
    void wrongMomentReselectsThatClipAndKeepsTheOthers() throws Exception {
        long id = upload(VIDEO);
        toReady(id);
        Clip clip = jobs.clips(id).getFirst();
        claude.then("clip-rejection", "{\"target\":\"selection\",\"notes\":\"Pick the funny part.\",\"hook_title\":\"\"}");
        review.reject(clip.id(), "wrong moment", null, null, BY);
        review.analyze(id);
        assertThat(status(id)).isEqualTo(SELECTING);
        claude.then("clip-select", "{\"clips\":[{\"start_line\":\"L0016\",\"end_line\":\"L0020\",\"hook_title\":\"Funny\"}]}");
        select.run(id);
        assertThat(claude.requests("clip-select").getLast().userPrompt()).contains("Pick the funny part.")
                .contains("L0006–L0010");
        assertThat(jobs.clip(clip.id()).hookTitle()).isEqualTo("Funny");
        render.run(id);
        format.run(id);
        assertThat(status(id)).isEqualTo(CLIP_READY);
    }

    @Test
    void postingEveryPlatformPublishesTheClipAndFinishesTheJob() throws Exception {
        long id = upload(VIDEO);
        claude.byDefault("clip-select",
                "{\"clips\":[{\"start_line\":\"L0001\",\"end_line\":\"L0005\",\"hook_title\":\"Only\"}]}");
        toReady(id);
        Clip clip = jobs.clips(id).getFirst();
        review.approve(clip.id(), BY);
        assertThatThrownBy(() -> review.markPosted(clip.id(), "tiktok", "https://example.com/x", BY))
                .isInstanceOf(InvalidInputException.class);
        review.markPosted(clip.id(), "youtube", LINK, BY);
        review.markPosted(clip.id(), "tiktok", "https://www.tiktok.com/@me/video/123", BY);
        assertThat(status(id)).isEqualTo(CLIP_READY);
        review.skip(clip.id(), "instagram", BY);
        assertThat(jobs.clip(clip.id()).status()).isEqualTo(CLIP_PUBLISHED);
        assertThat(status(id)).isEqualTo(CLIP_PUBLISHED);
    }

    @Test
    void captionEventsHaveTheNineFieldsTheirFormatNames() {
        String ass = new ClipRenderer(props).ass(List.of(new Word("Hello", 0, 1)), "A hook", null);
        assertThat(ass).contains("Hook,0,0,0,,A hook").contains("Caption,0,0,0,,{");
    }

    @Test
    void linesFollowSentencesAndPauses() {
        List<Word> w = List.of(new Word("Hello", 0, 0.5), new Word("there.", 0.5, 1), new Word("Next", 1.1, 1.5),
                new Word("bit", 1.5, 2), new Word("after", 3, 3.5), new Word("pause", 3.5, 4));
        List<ClipJobs.Line> lines = Transcript.lines(w, 0.7, 12);
        assertThat(lines).extracting(ClipJobs.Line::text).containsExactly("Hello there.", "Next bit", "after pause");
        assertThat(lines.getFirst().lineId()).isEqualTo("L0001");
        assertThat(Transcript.ordered(lines)).isTrue();
        assertThat(String.format(Locale.ROOT, "%.1f", Transcript.snap(10.0, List.of(9.7, 14.0), 0.5))).isEqualTo("9.7");
        assertThat(Transcript.snap(10.0, List.of(8.0), 0.5)).isEqualTo(10.0);
    }
}

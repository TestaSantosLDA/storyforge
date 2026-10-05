package com.storyforge.stage1;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.gpu.GpuLease;
import com.storyforge.image.ImageEngine;
import com.storyforge.image.ImageEngineUnavailableException;
import com.storyforge.image.ImageRequest;
import com.storyforge.image.ImageResult;
import com.storyforge.log.Attempt;
import com.storyforge.log.AttemptLog;
import com.storyforge.log.AttemptRepository;
import com.storyforge.storage.AssetStorage;
import com.storyforge.storage.InputHash;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Character reference sheets (Step 1A): the front view first, then the side view and an expression sheet generated
 * from it, so the whole sheet shows one design. Claude then looks at the three images and checks them.
 */
@Component
public class ReferenceSheets {

    public static final String VIEW_FRONT = "front";
    public static final String VIEW_SIDE = "side";
    public static final String VIEW_EXPRESSIONS = "expressions";

    /** One character's sheet: asset keys per view, its QA checks, and whether it passed. */
    public record Sheet(Map<String, String> views, List<QaCheck> checks, boolean passed, int attempts) {

        public long failures() {
            return checks.stream().filter(c -> !c.pass()).count();
        }

        public Sheet withAttempts(int n) {
            return new Sheet(views, checks, passed, n);
        }

        /** Views in sheet order (front, side, expressions); the database doesn't keep JSON key order. */
        public List<Map.Entry<String, String>> ordered() {
            return VIEWS.stream().filter(views::containsKey).map(v -> Map.entry(v, views.get(v))).toList();
        }
    }

    static final List<String> VIEWS = List.of(VIEW_FRONT, VIEW_SIDE, VIEW_EXPRESSIONS);

    private final ObjectProvider<ImageEngine> engine;
    private final AssetStorage assets;
    private final GpuLease gpu;
    private final AiCalls ai;
    private final AttemptLog attemptLog;
    private final AttemptRepository attempts;
    private final StoryforgeProperties.Images config;
    private final String style;

    ReferenceSheets(ObjectProvider<ImageEngine> engine, AssetStorage assets, GpuLease gpu, AiCalls ai,
            AttemptLog attemptLog, AttemptRepository attempts, StoryforgeProperties props) {
        this.engine = engine;
        this.assets = assets;
        this.gpu = gpu;
        this.ai = ai;
        this.attemptLog = attemptLog;
        this.attempts = attempts;
        this.config = props.images();
        this.style = props.style().block();
    }

    public int maxAttempts() {
        return config.sheetAttempts();
    }

    /** False when no image engine is configured; Gate A then shows that sheets are pending. */
    public boolean enabled() {
        return !"none".equals(config.engine()) && engine.getIfAvailable() != null;
    }

    /**
     * Generates and checks one character's sheet. A retry is a fresh draw with a new seed and the same prompt:
     * the failed checks are never put into the image prompt, because naming a flaw ("not plaid") makes the image
     * model draw it (seen in the first real run).
     *
     * @throws ImageEngineUnavailableException sidecar down or GPU busy too long: flag, never a strike
     */
    public Sheet generate(long storyId, int draftVersion, StoryContent.NewCharacter c, String roster, int attempt) {
        String description = c.name() + ": " + c.visualDescription();
        String prefix = "stories/" + storyId + "/sheets/v" + draftVersion + "/" + c.id() + "/a" + attempt + "/";
        long seed = Long.parseLong(InputHash.of(String.valueOf(storyId), c.id(), String.valueOf(attempt))
                .substring(0, 8), 16);
        int size = config.sheetSize();

        Map<String, String> views = new LinkedHashMap<>();
        String holder = "pipeline-a:story-" + storyId;
        acquireGpu(holder);
        try {
            byte[] front = image(storyId, prefix + VIEW_FRONT + ".png", new ImageRequest(
                    // Three-quarter rather than straight-on: a straight front view of a tailed animal gets a tail
                    // on each side (spike and 2026-10-05 run); three-quarter fixed it in 4 of 4 draws. Don't add
                    // "single tail" wording: it gave a gull a cat's tail.
                    "Character model sheet, single character, full body, three-quarter front view turned slightly "
                            + "to the left, standing, neutral pose, plain flat cream background, no other objects. "
                            + "Clean anatomy. "
                            + description + " " + style, size, size, seed, List.of(), 512));
            views.put(VIEW_FRONT, prefix + VIEW_FRONT + ".png");
            image(storyId, prefix + VIEW_SIDE + ".png", new ImageRequest(
                    "The same character as in the reference image, now in full-body side view profile facing left, "
                            + "standing, plain flat cream background. Keep every detail of the design identical. "
                            + description + " " + style, size, size, seed + 1, List.of(front), 512));
            views.put(VIEW_SIDE, prefix + VIEW_SIDE + ".png");
            image(storyId, prefix + VIEW_EXPRESSIONS + ".png", new ImageRequest(
                    "Expression sheet of the same character as in the reference image: three head-and-shoulders "
                            + "portraits side by side, each cropped at the chest, showing happy, surprised and sad, "
                            + "plain flat cream background. "
                            + "Keep every detail of the design identical. " + description + " " + style,
                    1360, 768, seed + 2, List.of(front), 512));
            views.put(VIEW_EXPRESSIONS, prefix + VIEW_EXPRESSIONS + ".png");
        } finally {
            gpu.release(holder);
        }

        List<Path> files = VIEWS.stream().map(v -> assets.localPath(views.get(v)).orElseThrow()).toList();
        List<QaCheck> checks = ai.judge(storyId, StoryStep.STAGE, "sheet_qa", "sheet-qa-v1", Map.of(
                "character", "- " + c.id() + " (" + c.name() + "): " + c.visualDescription(),
                "front_path", files.get(0).toAbsolutePath().toString(),
                "side_path", files.get(1).toAbsolutePath().toString(),
                "expressions_path", files.get(2).toAbsolutePath().toString(),
                "roster", roster, "style", style), files);
        return new Sheet(views, checks, QaCheck.allPass(checks), attempt);
    }

    /** Copies an approved sheet to the character's keep-forever location; returns the new keys. */
    public List<String> keepForever(String characterId, Map<String, String> views) {
        List<String> kept = new ArrayList<>();
        for (var e : new Sheet(views, List.of(), true, 0).ordered()) {
            String key = "characters/" + characterId + "/" + e.getKey() + ".png";
            try (InputStream in = assets.open(e.getValue()).orElseThrow()) {
                assets.put(key, in);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            kept.add(key);
        }
        return kept;
    }

    private byte[] image(long storyId, String key, ImageRequest req) {
        int attemptNo = (int) attempts.countByStoryIdAndStep(storyId, "sheet_image") + 1;
        Attempt attempt = new Attempt(storyId, StoryStep.STAGE, "sheet_image", attemptNo)
                .inputs(InputHash.of(req.prompt(), String.valueOf(req.width()), String.valueOf(req.height()),
                        String.valueOf(req.seed()), String.valueOf(req.references().size())),
                        Json.write(Map.of("prompt", req.prompt(), "seed", req.seed(), "key", key,
                                "references", req.references().size())));
        try {
            ImageResult r = engine.getObject().generate(req);
            assets.put(key, new ByteArrayInputStream(r.png()));
            attempt.versions(null, null, r.engineVersion());
            attemptLog.record(attempt.result("ok", null, null,
                    Json.write(Map.of("seconds", r.seconds(), "peak_vram_gb", String.valueOf(r.peakVramGb()))),
                    null, null));
            return r.png();
        } catch (ImageEngineUnavailableException e) {
            attemptLog.record(attempt.result("unavailable", null, null, null, null, e.getMessage()));
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void acquireGpu(String holder) {
        Instant deadline = Instant.now().plus(config.gpuWait());
        while (!gpu.tryAcquire(holder)) {
            if (Instant.now().isAfter(deadline)) {
                throw new ImageEngineUnavailableException("GPU busy for longer than " + config.gpuWait(), null);
            }
            try {
                Thread.sleep(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageEngineUnavailableException("interrupted waiting for the GPU", e);
            }
        }
    }
}

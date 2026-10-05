package com.storyforge.stage3;

import com.storyforge.character.CharacterFile;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.image.ImageEngine;
import com.storyforge.image.ImageRequest;
import com.storyforge.image.ImageResult;
import com.storyforge.stage1.AiCalls;
import com.storyforge.stage1.QaCheck;
import com.storyforge.storage.AssetStorage;
import com.storyforge.storage.InputHash;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Step 3B: one illustration per shot from the shot plan, the channel style and every character's approved reference
 * sheet; Claude opens the image and the references to check it. Up to {@code image-retries} draws, each a fresh
 * seed with the same prompt (flaws are never named in an image prompt; see the Stage 3 retry rules).
 */
@Component
public class ShotImages {

    private final ObjectProvider<ImageEngine> engine;
    private final AssetStorage assets;
    private final AiCalls ai;
    private final ShotStore store;
    private final StoryforgeProperties.Visuals v;
    private final String style;
    private final com.storyforge.notes.StoryNotes notes;

    ShotImages(ObjectProvider<ImageEngine> engine, AssetStorage assets, AiCalls ai, ShotStore store,
            StoryforgeProperties props, com.storyforge.notes.StoryNotes notes) {
        this.engine = engine;
        this.assets = assets;
        this.ai = ai;
        this.store = store;
        this.v = props.visuals();
        this.style = props.style().block();
        this.notes = notes;
    }

    /** True if the shot now has an image to animate (passing, or accepted earlier by a reviewer). */
    public boolean make(long storyId, ShotPlan.Shot shot, Map<String, CharacterFile> cast) {
        if (store.usable(storyId, shot.id()).isPresent()) {
            return true;
        }
        List<CharacterFile> inShot = shot.characters().stream().map(cast::get).toList();
        List<String> refKeys = references(inShot);
        List<byte[]> refs = refKeys.stream().map(this::read).toList();
        // A final-review correction for this shot, already phrased as what should be visible.
        String prompt = notes.get(storyId, "shot:" + shot.id())
                .map(n -> prompt(shot, inShot, refKeys).replace(style, n.strip() + " " + style))
                .orElseGet(() -> prompt(shot, inShot, refKeys));
        int previous = store.images(storyId, shot.id()).size();
        for (int i = 1; i <= v.imageRetries(); i++) {
            int attempt = previous + i;
            long seed = Long.parseLong(InputHash.of(String.valueOf(storyId), shot.id(), String.valueOf(attempt))
                    .substring(0, 8), 16);
            ImageResult r = engine.getObject().generate(new ImageRequest(prompt, v.imageWidth(), v.imageHeight(), seed,
                    refs, v.referenceMaxPx()));
            String hash = InputHash.of(prompt, String.join(",", refKeys), String.valueOf(seed), r.engineVersion());
            String key = "stories/" + storyId + "/shots/" + shot.id() + "/image-a" + attempt + ".png";
            put(key, r.png());
            List<QaCheck> checks = judge(storyId, shot, inShot, key, refKeys);
            int failures = (int) checks.stream().filter(c -> !c.pass()).count();
            store.addImage(storyId, shot.id(), attempt, hash, key, prompt, seed, r.engineVersion(),
                    ShotPlanner.JSON.writeValueAsString(checks), failures, failures == 0);
            if (failures == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Front + side per character for one or two characters, front only for three or four; at most
     * {@code max-reference-images} (spike: 4 references at 512 px fit a 6 GB card).
     */
    List<String> references(List<CharacterFile> inShot) {
        List<String> keys = new ArrayList<>();
        for (CharacterFile c : inShot) {
            List<String> sheet = c.referenceImages() == null ? List.of() : c.referenceImages();
            sheet.stream().limit(inShot.size() <= 2 ? 2 : 1).forEach(keys::add);
        }
        return keys.stream().limit(v.maxReferenceImages()).filter(assets::exists).toList();
    }

    /** Every character's action as its own sentence naming them (Stage 3 "Action phrasing"). */
    String prompt(ShotPlan.Shot shot, List<CharacterFile> inShot, List<String> refKeys) {
        StringBuilder p = new StringBuilder();
        p.append(framing(shot.framing())).append(" storybook illustration, 16:9. ").append(shot.setting()).append(' ');
        shot.actions().forEach(a -> p.append(a.action().strip()).append(' '));
        if (inShot.isEmpty()) {
            p.append("No characters in this picture. ");
        } else {
            int slot = 1;
            for (CharacterFile c : inShot) {
                long n = refKeys.stream().filter(k -> k.startsWith("characters/" + c.id() + "/")).count();
                if (n > 0) {
                    p.append(c.name()).append(" is the character in reference image")
                            .append(n > 1 ? "s " + slot + " and " + (slot + 1) : " " + slot)
                            .append("; keep the design exactly as in the reference. ");
                    slot += n;
                }
                p.append(c.name()).append(": ").append(c.visualDescription().strip()).append(' ');
            }
            p.append("Only these characters appear, each once. ");
        }
        return p.append(style).toString();
    }

    private static String framing(String f) {
        return switch (f == null ? "" : f) {
            case "close_up" -> "Close-up";
            case "medium" -> "Medium shot";
            default -> "Wide shot";
        };
    }

    private List<QaCheck> judge(long storyId, ShotPlan.Shot shot, List<CharacterFile> inShot, String key,
            List<String> refKeys) {
        List<Path> files = new ArrayList<>();
        files.add(assets.localPath(key).orElseThrow());
        refKeys.forEach(k -> files.add(assets.localPath(k).orElseThrow()));
        Map<String, String> names = new LinkedHashMap<>();
        inShot.forEach(c -> names.put(c.id(), c.name()));
        String actions = inShot.isEmpty() ? "- (no characters)" : shot.actions().stream()
                .map(a -> "- " + names.getOrDefault(a.character(), a.character()) + ": " + a.action())
                .collect(Collectors.joining("\n"));
        String references = refKeys.isEmpty() ? "(none)" : refKeys.stream()
                .map(k -> "- " + k.split("/")[1] + " " + k.substring(k.lastIndexOf('/') + 1, k.length() - 4) + ": "
                        + assets.localPath(k).orElseThrow().toAbsolutePath())
                .collect(Collectors.joining("\n"));
        return ai.judge(storyId, "stage3", "shot_qa", "shot-qa-v1", Map.of(
                "shot_path", files.getFirst().toAbsolutePath().toString(), "setting", shot.setting(),
                "framing", shot.framing(), "actions", actions, "references", references, "style", style), files);
    }

    private void put(String key, byte[] data) {
        try {
            assets.put(key, new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] read(String key) {
        try (InputStream in = assets.open(key).orElseThrow()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

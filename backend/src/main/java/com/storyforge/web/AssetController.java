package com.storyforge.web;

import com.storyforge.storage.AssetStorage;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Serves generated images to the review pages. Keys are validated by the storage (no path escapes). */
@Controller
class AssetController {

    private final AssetStorage assets;

    AssetController(AssetStorage assets) {
        this.assets = assets;
    }

    /** Videos are served as files so the player can seek (range requests); subtitles as WebVTT for the player. */
    @GetMapping("/media")
    ResponseEntity<org.springframework.core.io.Resource> media(@RequestParam String key) {
        try {
            var path = assets.localPath(key).orElse(null);
            if (path == null) {
                return ResponseEntity.notFound().build();
            }
            if (key.endsWith(".mp4")) {
                return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4"))
                        .body(new org.springframework.core.io.FileSystemResource(path));
            }
            if (key.endsWith(".srt")) {
                String srt = java.nio.file.Files.readString(path);
                String vtt = "WEBVTT\n\n" + srt.replaceAll("(\\d\\d:\\d\\d:\\d\\d),(\\d\\d\\d)", "$1.$2");
                return ResponseEntity.ok().contentType(MediaType.valueOf("text/vtt;charset=UTF-8"))
                        .body(new org.springframework.core.io.ByteArrayResource(vtt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | java.io.IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/assets")
    ResponseEntity<InputStreamResource> asset(@RequestParam String key) throws IOException {
        MediaType type = key.endsWith(".png") ? MediaType.IMAGE_PNG
                : key.endsWith(".wav") ? MediaType.valueOf("audio/wav")
                : key.endsWith(".mp4") ? MediaType.valueOf("video/mp4") : null;
        if (type == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            InputStream in = assets.open(key).orElse(null);
            return in == null ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok().contentType(type).body(new InputStreamResource(in));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

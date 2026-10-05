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

    @GetMapping("/assets")
    ResponseEntity<InputStreamResource> asset(@RequestParam String key) throws IOException {
        if (!key.endsWith(".png")) {
            return ResponseEntity.notFound().build();
        }
        try {
            InputStream in = assets.open(key).orElse(null);
            return in == null ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new InputStreamResource(in));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

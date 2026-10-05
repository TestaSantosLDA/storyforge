package com.storyforge.image;

import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the sidecar's {@code POST /image}. */
public class SidecarImageEngine implements ImageEngine {

    private final RestClient http;

    public SidecarImageEngine(RestClient http) {
        this.http = http;
    }

    @Override
    public ImageResult generate(ImageRequest req) {
        Map<String, Object> body = Map.of(
                "prompt", req.prompt(), "width", req.width(), "height", req.height(), "seed", req.seed(),
                "references", req.references().stream().map(b -> Base64.getEncoder().encodeToString(b)).toList(),
                "reference_max_px", req.referenceMaxPx());
        try {
            ResponseEntity<byte[]> r = http.post().uri("/image").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toEntity(byte[].class);
            String peak = r.getHeaders().getFirst("X-Peak-Vram-Gb");
            String seconds = r.getHeaders().getFirst("X-Seconds");
            return new ImageResult(r.getBody(), r.getHeaders().getFirst("X-Engine-Version"),
                    seconds == null ? 0 : Double.parseDouble(seconds), peak == null ? null : Double.valueOf(peak));
        } catch (RestClientException e) {
            throw new ImageEngineUnavailableException("image sidecar: " + e.getMessage(), e);
        }
    }
}

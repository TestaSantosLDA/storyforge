package com.storyforge.voice;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Kokoro ({@code POST /tts}) and Whisper ({@code POST /transcribe}) in the sidecar. */
public class SidecarSpeech implements VoiceEngine, Transcriber {

    private final RestClient http;

    public SidecarSpeech(RestClient http) {
        this.http = http;
    }

    @Override
    public SpeechClip synthesize(String text, String voice, double speed) {
        try {
            ResponseEntity<byte[]> r = http.post().uri("/tts").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "voice", voice, "speed", speed)).retrieve().toEntity(byte[].class);
            var h = r.getHeaders();
            return new SpeechClip(r.getBody(), h.getFirst("X-Engine-Version"), num(h.getFirst("X-Duration")),
                    num(h.getFirst("X-Peak")), num(h.getFirst("X-Max-Silence")),
                    (int) num(h.getFirst("X-Clipped-Samples")));
        } catch (RestClientException e) {
            throw new SpeechUnavailableException("voice sidecar: " + e.getMessage(), e);
        }
    }

    @Override
    public Transcript transcribe(byte[] wav) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> body = http.post().uri("/transcribe").contentType(MediaType.valueOf("audio/wav"))
                    .body(wav).retrieve().body(Map.class);
            return new Transcript(body.get("text"), body.get("engine_version"));
        } catch (RestClientException e) {
            throw new SpeechUnavailableException("transcription sidecar: " + e.getMessage(), e);
        }
    }

    private static double num(String s) {
        return s == null ? 0 : Double.parseDouble(s);
    }
}

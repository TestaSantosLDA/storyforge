package com.storyforge.voice;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Kokoro ({@code POST /tts}) and Whisper ({@code POST /transcribe}) in the sidecar. */
public class SidecarSpeech implements VoiceEngine, Transcriber, WordTranscriber {

    private final RestClient http;
    private final RestClient longHttp;

    /** {@code longHttp} has the long read timeout a whole video needs. */
    public SidecarSpeech(RestClient http, RestClient longHttp) {
        this.http = http;
        this.longHttp = longHttp;
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

    @Override
    public Words transcribeWords(java.nio.file.Path media) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = longHttp.post().uri("/transcribe/words").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("path", media.toAbsolutePath().toString())).retrieve().body(Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) body.get("words");
            List<Word> words = raw.stream().map(w -> new Word((String) w.get("text"),
                    ((Number) w.get("start")).doubleValue(), ((Number) w.get("end")).doubleValue())).toList();
            return new Words((String) body.get("language"), words, (String) body.get("engine_version"));
        } catch (RestClientException e) {
            throw new SpeechUnavailableException("transcription sidecar: " + e.getMessage(), e);
        }
    }

    private static double num(String s) {
        return s == null ? 0 : Double.parseDouble(s);
    }
}

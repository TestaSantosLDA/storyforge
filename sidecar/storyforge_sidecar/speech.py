"""Kokoro-82M text-to-speech (Apache 2.0) and faster-whisper transcription (MIT), loaded on first use."""
import io
import threading

import numpy as np
import soundfile as sf

SAMPLE_RATE = 24000
TTS_VERSION = "kokoro-82m/0.9.4"
STT_MODEL = "small.en"
STT_VERSION = f"faster-whisper-1.2.1/{STT_MODEL}"
# Clips from any video: multilingual model, language auto-detected.
WORDS_MODEL = "small"
WORDS_VERSION = f"faster-whisper-1.2.1/{WORDS_MODEL}"

# Kokoro English voices. a* = American English pipeline, b* = British English pipeline.
VOICES = [
    "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
    "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
    "am_puck", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
]

TARGET_RMS_DBFS = -20.0
SILENCE_DBFS = -45.0


class Speech:
    def __init__(self, device: str):
        self.device = device
        self._pipelines = {}
        self._whisper = None
        self._words_whisper = None
        self._lock = threading.Lock()

    @property
    def tts_loaded(self) -> bool:
        return bool(self._pipelines)

    @property
    def stt_loaded(self) -> bool:
        return self._whisper is not None or self._words_whisper is not None

    def synthesize(self, text: str, voice: str, speed: float = 1.0) -> tuple[bytes, dict]:
        if voice not in VOICES:
            raise ValueError(f"unknown voice {voice}")
        with self._lock:
            pipe = self._pipeline(voice[0])
            chunks = [audio.numpy() for _, _, audio in pipe(text, voice=voice, speed=speed)]
        if not chunks:
            raise ValueError("no audio produced")
        raw = np.concatenate(chunks)
        audio = normalize(raw)
        stats = analyse(audio)
        # Clipping is judged on the engine's raw output, before our gain change.
        stats["clipped_samples"] = int(np.count_nonzero(np.abs(raw) >= 0.999))
        buf = io.BytesIO()
        sf.write(buf, audio, SAMPLE_RATE, subtype="PCM_16", format="WAV")
        return buf.getvalue(), stats

    def transcribe(self, wav: bytes) -> str:
        with self._lock:
            if self._whisper is None:
                from faster_whisper import WhisperModel

                cuda = self.device == "cuda"
                self._whisper = WhisperModel(STT_MODEL, device="cuda" if cuda else "cpu",
                                             compute_type="float16" if cuda else "int8")
            segments, _ = self._whisper.transcribe(io.BytesIO(wav), language="en", beam_size=5)
            return " ".join(s.text.strip() for s in segments).strip()

    def transcribe_words(self, path: str) -> dict:
        """Word-level timestamps for a whole media file; Whisper decodes the audio itself."""
        with self._lock:
            if self._words_whisper is None:
                from faster_whisper import WhisperModel

                cuda = self.device == "cuda"
                self._words_whisper = WhisperModel(WORDS_MODEL, device="cuda" if cuda else "cpu",
                                                   compute_type="float16" if cuda else "int8")
            segments, info = self._words_whisper.transcribe(path, beam_size=5, word_timestamps=True, vad_filter=True)
            words = [{"text": w.word.strip(), "start": round(w.start, 3), "end": round(w.end, 3)}
                     for s in segments for w in (s.words or []) if w.word.strip()]
        return {"language": info.language, "words": words, "engine_version": WORDS_VERSION}

    def _pipeline(self, lang: str):
        if lang not in self._pipelines:
            from kokoro import KPipeline

            try:
                self._pipelines[lang] = KPipeline(lang_code=lang, repo_id="hexgrad/Kokoro-82M", device=self.device)
            except RuntimeError:
                # Seen once on the first load in a fresh server process ("meta tensor"); a second try succeeded.
                self._pipelines[lang] = KPipeline(lang_code=lang, repo_id="hexgrad/Kokoro-82M", device=self.device)
        return self._pipelines[lang]


def normalize(audio: np.ndarray) -> np.ndarray:
    """Consistent loudness: scale to a target RMS, never past full scale (Stage 2 'normalized to a consistent loudness')."""
    rms = float(np.sqrt(np.mean(audio ** 2))) if audio.size else 0.0
    if rms <= 0:
        return audio
    gain = 10 ** (TARGET_RMS_DBFS / 20) / rms
    peak = float(np.max(np.abs(audio))) * gain
    if peak > 0.98:
        gain *= 0.98 / peak
    return (audio * gain).astype(np.float32)


def analyse(audio: np.ndarray) -> dict:
    """Numbers for the per-line QA: duration, peak, and the longest silent gap inside the clip."""
    duration = audio.size / SAMPLE_RATE
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    frame = int(0.02 * SAMPLE_RATE)
    n = audio.size // frame
    longest = 0.0
    if n:
        frames = audio[: n * frame].reshape(n, frame)
        db = 20 * np.log10(np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-9)
        voiced = np.nonzero(db > SILENCE_DBFS)[0]
        if voiced.size:
            inner = db[voiced[0]: voiced[-1] + 1] <= SILENCE_DBFS
            run = best = 0
            for silent in inner:
                run = run + 1 if silent else 0
                best = max(best, run)
            longest = best * frame / SAMPLE_RATE
    return {"duration": round(duration, 3), "peak": round(peak, 4), "max_silence": round(longest, 3)}

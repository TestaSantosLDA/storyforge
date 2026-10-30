"""API contract tests with a stub engine (no GPU, no weights). The real model is exercised by the smoke run."""
import base64
import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from storyforge_sidecar import app as app_module
from storyforge_sidecar.image_engine import ImageResult, _shrink


class StubEngine:
    version = "stub-1"
    loaded = True

    def __init__(self, error=None):
        self.calls = []
        self.error = error

    def generate(self, prompt, width, height, seed, steps, refs, ref_max_px):
        if self.error:
            raise self.error
        self.calls.append(dict(prompt=prompt, width=width, height=height, seed=seed, refs=refs))
        buf = io.BytesIO()
        Image.new("RGB", (width, height), "white").save(buf, format="PNG")
        return ImageResult(buf.getvalue(), 1.5, 4.7, self.version)


@pytest.fixture
def client(monkeypatch):
    stub = StubEngine()
    monkeypatch.setattr(app_module, "IMAGE", stub)
    return TestClient(app_module.app), stub


def png_b64(w=64, h=64):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), "red").save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


def test_health_reports_device_and_model(client):
    c, _ = client
    body = c.get("/health").json()
    assert body["status"] == "ok"
    assert body["device"] in ("cuda", "mps", "cpu")
    assert body["models"]["image"]["version"] == "stub-1"


def test_image_returns_png_with_provenance_headers(client):
    c, stub = client
    r = c.post("/image", json={"prompt": "a fox", "width": 1360, "height": 768, "seed": 42,
                               "references": [png_b64(), png_b64()]})
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert r.headers["X-Engine-Version"] == "stub-1"
    assert r.headers["X-Seed"] == "42"
    assert Image.open(io.BytesIO(r.content)).size == (1360, 768)
    assert len(stub.calls[0]["refs"]) == 2


def test_bad_requests_are_rejected(client):
    c, _ = client
    assert c.post("/image", json={"prompt": "", "width": 512, "height": 512}).status_code == 422
    assert c.post("/image", json={"prompt": "x", "width": 500, "height": 512}).status_code == 422
    assert c.post("/image", json={"prompt": "x", "width": 512, "height": 512,
                                  "references": [png_b64()] * 5}).status_code == 422
    assert c.post("/image", json={"prompt": "x", "width": 512, "height": 512,
                                  "references": ["not base64!"]}).status_code == 422


def test_out_of_memory_and_missing_weights_have_distinct_statuses(monkeypatch):
    for error, status in ((RuntimeError("CUDA out of memory"), 507), (FileNotFoundError("no weights"), 503)):
        monkeypatch.setattr(app_module, "IMAGE", StubEngine(error))
        r = TestClient(app_module.app).post("/image", json={"prompt": "x", "width": 512, "height": 512})
        assert r.status_code == status


def test_references_are_shrunk_to_the_limit():
    assert _shrink(Image.new("RGB", (1024, 768)), 512).size == (512, 384)
    assert _shrink(Image.new("RGB", (300, 200)), 512).size == (300, 200)


class StubSpeech:
    tts_loaded = stt_loaded = True

    def synthesize(self, text, voice, speed):
        import numpy as np
        import soundfile as sf
        buf = io.BytesIO()
        sf.write(buf, np.zeros(2400, dtype="float32"), 24000, subtype="PCM_16", format="WAV")
        return buf.getvalue(), {"duration": 0.1, "peak": 0.5, "max_silence": 0.0, "clipped_samples": 0}

    def transcribe(self, wav):
        return "hello there"


def test_tts_and_transcribe(monkeypatch):
    monkeypatch.setattr(app_module, "SPEECH", StubSpeech())
    c = TestClient(app_module.app)
    r = c.post("/tts", json={"text": "Hello there.", "voice": "bm_george"})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav"
    assert r.headers["X-Duration"] == "0.1" and r.headers["X-Clipped-Samples"] == "0"
    assert c.post("/tts", json={"text": "x", "voice": "nobody"}).status_code == 422
    assert c.post("/transcribe", content=r.content).json()["text"] == "hello there"
    assert "bm_george" in c.get("/voices").json()["voices"]


def test_silence_analysis_finds_inner_gaps():
    import numpy as np
    from storyforge_sidecar.speech import analyse
    tone = 0.3 * np.sin(np.linspace(0, 2000, 24000)).astype("float32")
    audio = np.concatenate([tone, np.zeros(48000, dtype="float32"), tone])
    stats = analyse(audio)
    assert 1.9 <= stats["max_silence"] <= 2.1
    assert stats["duration"] == 4.0


def test_transcribe_words_refuses_a_missing_file(client):
    c, _ = client
    assert c.post("/transcribe/words", json={"path": "/no/such/video.mp4"}).status_code == 422


def test_transcribe_words_returns_language_and_words(client, monkeypatch, tmp_path):
    c, _ = client
    video = tmp_path / "video.mp4"
    video.write_bytes(b"x")

    class Stub:
        def transcribe_words(self, path):
            return {"language": "pt", "words": [{"text": "Ola", "start": 0.0, "end": 0.4}], "engine_version": "stub"}

    monkeypatch.setattr(app_module, "SPEECH", Stub())
    body = c.post("/transcribe/words", json={"path": str(video)}).json()
    assert body["language"] == "pt" and body["words"][0]["text"] == "Ola"

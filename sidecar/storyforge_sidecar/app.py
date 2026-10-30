"""Storyforge sidecar: local HTTP API for Python-only models. Bounded calls; the Spring app owns the flow."""
import base64
import binascii
import os

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

from fastapi import Request

from .device import pick_device, vram_info
from .image_engine import KleinEngine, models_dir
from .speech import STT_VERSION, TTS_VERSION, VOICES, Speech

app = FastAPI(title="storyforge-sidecar")

DEVICE = pick_device()
IMAGE = KleinEngine(models_dir(), DEVICE, os.environ.get("STORYFORGE_IMAGE_QUANT", "nf4"))
SPEECH = Speech(DEVICE)


class ImageRequest(BaseModel):
    prompt: str = Field(min_length=1)
    width: int = Field(ge=256, le=2048, multiple_of=16)
    height: int = Field(ge=256, le=2048, multiple_of=16)
    seed: int = 0
    steps: int = Field(default=4, ge=1, le=50)
    # Base64-encoded PNG/JPEG reference images (character sheets), at most 4.
    references: list[str] = Field(default_factory=list, max_length=4)
    reference_max_px: int = Field(default=512, ge=128, le=1024)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": DEVICE,
        "gpu": vram_info(DEVICE),
        "models": {
            "image": {"version": IMAGE.version, "loaded": IMAGE.loaded},
            "tts": {"version": TTS_VERSION, "loaded": SPEECH.tts_loaded},
            "stt": {"version": STT_VERSION, "loaded": SPEECH.stt_loaded},
        },
    }


@app.post("/image", responses={200: {"content": {"image/png": {}}}})
def image(req: ImageRequest):
    try:
        refs = [base64.b64decode(r, validate=True) for r in req.references]
    except binascii.Error:
        raise HTTPException(422, "references must be base64")
    try:
        result = IMAGE.generate(req.prompt, req.width, req.height, req.seed, req.steps, refs, req.reference_max_px)
    except FileNotFoundError as e:
        raise HTTPException(503, str(e))
    except RuntimeError as e:
        # CUDA out of memory and friends: the caller retries at lower settings, then flags (Stage 3 scenario 8).
        status = 507 if "out of memory" in str(e).lower() else 500
        raise HTTPException(status, str(e)[:500])
    headers = {
        "X-Engine-Version": result.engine_version,
        "X-Seconds": str(result.seconds),
        "X-Seed": str(req.seed),
    }
    if result.peak_vram_gb is not None:
        headers["X-Peak-Vram-Gb"] = str(result.peak_vram_gb)
    return Response(result.png, media_type="image/png", headers=headers)


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    voice: str
    speed: float = Field(default=1.0, ge=0.5, le=2.0)


@app.get("/voices")
def voices():
    return {"engine": TTS_VERSION, "voices": VOICES}


@app.post("/tts", responses={200: {"content": {"audio/wav": {}}}})
def tts(req: TtsRequest):
    if req.voice not in VOICES:
        raise HTTPException(422, f"unknown voice {req.voice}")
    try:
        wav, stats = SPEECH.synthesize(req.text, req.voice, req.speed)
    except ValueError as e:
        raise HTTPException(422, str(e))
    headers = {"X-Engine-Version": TTS_VERSION, "X-Duration": str(stats["duration"]), "X-Peak": str(stats["peak"]),
               "X-Max-Silence": str(stats["max_silence"]), "X-Clipped-Samples": str(stats["clipped_samples"])}
    return Response(wav, media_type="audio/wav", headers=headers)


@app.post("/transcribe")
async def transcribe(request: Request):
    wav = await request.body()
    if not wav:
        raise HTTPException(422, "send the WAV file as the request body")
    return {"text": SPEECH.transcribe(wav), "engine_version": STT_VERSION}

# Storyforge sidecar

Local Python service for the models that only exist in Python. Runs natively, never in Docker
(Docker on a Mac can't use the Apple GPU). The Spring app calls it on `http://127.0.0.1:8765`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Device in use and which models are loaded |
| `POST /image` | Flux 2 Klein 4B (Apache 2.0) image, optionally conditioned on reference images |

| `POST /tts` | Kokoro text-to-speech |
| `POST /transcribe` | Whisper transcript of a WAV, for the Stage 2 check |
| `POST /transcribe/words` | Whisper word timings and language of a local media file, for clips from any video |

`requirements.txt` also installs yt-dlp, which the Java app runs to fetch YouTube links.

## Setup

```bash
cd sidecar
python -m venv .venv
.venv/Scripts/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128   # NVIDIA
# Mac: .venv/bin/pip install torch torchvision
.venv/Scripts/pip install -r requirements.txt
```

Model weights live outside the repo in `STORYFORGE_MODELS_DIR` (default `~/storyforge-models`), with
Flux 2 Klein 4B in `flux2-klein-4b/` (diffusers layout from `black-forest-labs/FLUX.2-klein-4B`).

## Run

```bash
.venv/Scripts/python -m uvicorn storyforge_sidecar.app:app --host 127.0.0.1 --port 8765
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `STORYFORGE_MODELS_DIR` | `~/storyforge-models` | Model weights |
| `STORYFORGE_DEVICE` | auto | `cuda`, `mps` or `cpu`; auto picks CUDA, then Apple MPS, then CPU |
| `STORYFORGE_IMAGE_QUANT` | `nf4` | `nf4` (fits 6 GB) or `none` (bf16, needs a large GPU) |

"""Flux 2 Klein 4B (Apache 2.0) image generation, as validated by the 2026-10-04 spike.

NF4-quantized transformer and text encoder with model CPU offload: peak ~4.7 GB with four 512 px references,
so it fits a 6 GB card. Loaded once, on first use; calls are serialized because there is one GPU.
"""
import io
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

MODEL_DIR_NAME = "flux2-klein-4b"
ENGINE_VERSION = "flux2-klein-4b/diffusers-0.40/nf4"


@dataclass
class ImageResult:
    png: bytes
    seconds: float
    peak_vram_gb: float | None
    engine_version: str


class KleinEngine:
    def __init__(self, models_dir: Path, device: str, quant: str = "nf4"):
        self.model_path = models_dir / MODEL_DIR_NAME
        self.device = device
        self.quant = quant
        self._pipe = None
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self._pipe is not None

    @property
    def version(self) -> str:
        return ENGINE_VERSION if self.quant == "nf4" else ENGINE_VERSION.replace("/nf4", "/bf16")

    def _load(self):
        import torch
        from diffusers import Flux2KleinPipeline

        if not self.model_path.is_dir():
            raise FileNotFoundError(f"Flux 2 Klein weights not found in {self.model_path}")
        kw = {}
        if self.quant == "nf4" and self.device == "cuda":
            from diffusers import BitsAndBytesConfig as DiffBnb
            from diffusers.models import Flux2Transformer2DModel
            from transformers import AutoModel, BitsAndBytesConfig as TfBnb

            kw["transformer"] = Flux2Transformer2DModel.from_pretrained(
                self.model_path, subfolder="transformer", torch_dtype=torch.bfloat16,
                quantization_config=DiffBnb(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                            bnb_4bit_compute_dtype=torch.bfloat16))
            kw["text_encoder"] = AutoModel.from_pretrained(
                self.model_path, subfolder="text_encoder", torch_dtype=torch.bfloat16,
                quantization_config=TfBnb(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                          bnb_4bit_compute_dtype=torch.bfloat16))
        pipe = Flux2KleinPipeline.from_pretrained(self.model_path, torch_dtype=torch.bfloat16, **kw)
        if self.device == "cuda":
            pipe.enable_model_cpu_offload()
        else:
            pipe.to(self.device)
        self._pipe = pipe

    def generate(self, prompt: str, width: int, height: int, seed: int, steps: int = 4,
                 refs: list[bytes] | None = None, ref_max_px: int = 512) -> ImageResult:
        import torch

        with self._lock:
            if self._pipe is None:
                self._load()
            images = [_shrink(Image.open(io.BytesIO(b)).convert("RGB"), ref_max_px) for b in (refs or [])]
            if self.device == "cuda":
                torch.cuda.reset_peak_memory_stats()
            start = time.time()
            out = self._pipe(
                prompt=prompt, image=images or None, width=width, height=height,
                num_inference_steps=steps, guidance_scale=1.0,
                generator=torch.Generator("cpu").manual_seed(seed),
            ).images[0]
            seconds = time.time() - start
            peak = round(torch.cuda.max_memory_reserved() / 2**30, 2) if self.device == "cuda" else None
        buf = io.BytesIO()
        out.save(buf, format="PNG")
        return ImageResult(buf.getvalue(), round(seconds, 1), peak, self.version)


def _shrink(img: Image.Image, max_px: int) -> Image.Image:
    """Reference images at most max_px on the long side (spike: keeps 4 refs within a 6 GB card)."""
    scale = max_px / max(img.size)
    if scale >= 1:
        return img
    return img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)


def models_dir() -> Path:
    return Path(os.environ.get("STORYFORGE_MODELS_DIR", Path.home() / "storyforge-models"))

"""Compute device: auto-detected (CUDA, then Apple MPS, then CPU) and overridable (CLAUDE.md repo hygiene)."""
import os


def pick_device(override: str | None = None) -> str:
    choice = (override or os.environ.get("STORYFORGE_DEVICE") or "auto").lower()
    if choice != "auto":
        return choice
    import torch

    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def vram_info(device: str) -> dict:
    """Device memory in GiB, where the platform can tell us."""
    if device != "cuda":
        return {}
    import torch

    free, total = torch.cuda.mem_get_info()
    return {
        "name": torch.cuda.get_device_name(0),
        "total_gb": round(total / 2**30, 2),
        "free_gb": round(free / 2**30, 2),
    }

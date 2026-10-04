# Flux 2 Klein 4B character-consistency spike (2026-10-04)

**Verdict: PASS for keeping a character on-model, with three conditions (below).** Marginal for two-character interaction shots.

## Setup
- RTX 3060 Ti **8 GB** (docs assume 6 GB), Windows 11, Python 3.11, PyTorch 2.11 cu128, diffusers 0.40, bitsandbytes 0.50.
- `black-forest-labs/FLUX.2-klein-4B` (Apache 2.0), 4 steps, guidance 1.0. Transformer and Qwen3 text encoder both NF4 (bitsandbytes), model CPU offload.
- Characters: `characters/bramble.yaml` (fox) and `characters/hazel.yaml` (owl), original designs, style block from docs/00-overview.md.
- Technique tested: reference images (front + side per character) + full visual description in every prompt + fixed seeds. Baseline: same prompt, no references. LoRA not tried (would be the fallback).

## Numbers (PyTorch peak reserved VRAM, seconds per image incl. text encoding)
| Job | Refs | VRAM | Time |
| --- | --- | --- | --- |
| Text only, 1024² or 1360×768 | 0 | 4.4 GB | 8–10 s |
| Side view / expressions | 1 | 4.7 GB | 17 s |
| 1 character | 2 full-size | 5.7 GB | 24 s |
| 2 characters | 4 full-size | **7.8 GB** | **52–66 s** (spills to shared memory) |
| 2 characters | 4 at 512 px | 4.7 GB | 17 s |
Model load: 24 s. 55 images, 0 crashes, 0 OOM.

## Results (30 reference images + 10 text-only, manually scored against identity_markers)
- With refs: identity held in every image (fur, dark legs, green eyes, blue striped scarf, acorn button; owl spectacles + green shawl).
- Shot usable within 3 tries: **9/10**; on try 1: **7/10**.
- Failures: anatomy glitches 2/30 (two tails, three ears), duplicate owl 2/6 two-char tries, extra butterfly 1, blank eye 1.
- s09 "owl reads to fox": the fox held the book in 3/3 tries. Characters correct, action wrong.
- Text-only: 3/10 clearly off-model (plaid scarf; fox and owl swap features; duplicate owl), acorn button lost or generic in most.
- Never rendered in 40/40: the small left-ear notch. Fine details are not identity markers.
- The "two tails" in the front reference sheet propagated into scenes. Reference flaws get copied.
- 512 px refs: identity still holds but slightly weaker (owl grew a fox-like tail in s08).

## Conditions
1. Gate A / reference QA must check anatomy (tail/ear/limb count), not just the description, because flaws propagate.
2. Identity markers in character files: large shapes and colours only; nothing smaller than an ear.
3. VRAM: on a 6 GB card, cap references at ~4 images of 512 px per shot (1 front ref per character for 3–4 character shots). Untested: 3–4 character shots.

## Fallback if real stories fail QA too often
Train a small LoRA per recurring character on the Klein 4B base weights (Apache 2.0) from the approved sheet; keep references on top. Second option: fewer characters per shot (planner limit 2 instead of 4) and action phrased per character.

## Files
The spike code and images are not committed (`spikes/` is git-ignored). Contact sheets and the full report are in the project library: `contact_sheet_scenes.jpg`, `contact_sheet_references.jpg`, `REPORT.md`. Scoring was done by eye against the character files' identity markers, not by the Stage 3 vision QA, which did not exist yet.

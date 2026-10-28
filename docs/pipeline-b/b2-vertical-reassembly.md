# Pipeline B · Stage 2 — Vertical Re-assembly

Stage B2 rebuilds each selected slice as a vertical 9:16 video. The shots are re-generated as vertical illustrations (never cropped), animated in storybook style, and combined with the original narration, music, burned-in captions, and the fixed closing line.

## Position in the pipeline

- B1 Clip Selection → **B2 Vertical Re-assembly** → B3 Per-Platform Formatting.
- Trigger: clip status `rendering`. Uses the GPU lock (Pipeline A has priority).

## Inputs

- Slice (start/end lines) and hook title from B1.
- From Pipeline A: shot list and shot descriptions, character reference images, per-line audio clips, timing manifest, music used per scene.
- Channel assets: style prompt block, closing-line audio, caption style.

## Steps

1. **Vertical images** — each shot in the slice is re-generated as a 9:16 image with Flux 2 Klein, from the same shot description, style block and character references. Characters are framed in the central **safe zone**, clear of the areas covered by platform buttons and captions.
2. **Animation** — storybook camera moves via the same `AnimationEngine` as Stage 3; clip lengths taken from the manifest.
3. **Audio** — the original Stage 2 line clips for the slice (no new speech), then the **fixed closing line** ("Watch the rest of the episode on our channel!"), recorded once in the narrator voice and approved at setup.
4. **Music** — the same track Pipeline A used for those scenes, trimmed with fade in and out, ducked under speech.
5. **Captions** — burned into the picture, line by line from the script text and manifest timings, large and readable, inside the safe zone.
6. **Overlays** — optional hook title for the first \~3 seconds (config on/off); an end text card during the closing line pointing to the full episode.
7. **Render** — MP4, 1080×1920, H.264/AAC.

## Automated QA

| Check | Pass condition |
| --- | --- |
| Images | Same checks as Stage 3 (characters, consistency, style, artifacts, safety, trademark) plus vertical framing: characters inside the safe zone |
| Length | Under 60 seconds including the closing line |
| Format | 1080×1920, configured frame rate |
| Sync | Shots and captions match the manifest timings |
| Captions | Every line present, inside the safe zone, readable size |
| Audio | Loudness on target; music never louder than voice |
| Frames | No black or frozen-blank frames |

A failing shot is regenerated up to 3 times; then `needs_attention`. Vertical images are cached, so retries and re-renders only redo what changed.

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Happy path | All shots pass, clip rendered | `formatting` (B3) |
| 2 | Character placed where the platform buttons would cover it | Framing check fails; regenerate that shot | retry |
| 3 | Character drifts from references in the vertical version | Consistency fails; regenerate | retry |
| 4 | Clip runs over 60 s | Should be prevented by B1; if it happens, send back to B1 for a shorter slice | `selecting` |
| 5 | Shot fails 3 times | Flag; options as in Stage 3 (retry, edit shot description, accept) | `needs_attention` |
| 6 | GPU taken by Pipeline A | Clip waits, then continues | `rendering` |
| 7 | Clip rejected in B4 for visuals or captions | Only the affected shots or captions are redone | `rendering` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `rendering` | QA passes | `formatting` (B3) |
| `rendering` | Over length | `selecting` (B1) |
| `rendering` | Shot fails 3 times | `needs_attention` |
| `needs_attention` | Retry, edit, or accept | `rendering` |

## Decisions

- Shots re-generated in 9:16 with the same references; never cropped from 16:9.
- Original narration reused; no narrator intro; one fixed closing line on every clip.
- Burned-in captions and an optional on-screen hook title.
- Same music as the long-form scenes.

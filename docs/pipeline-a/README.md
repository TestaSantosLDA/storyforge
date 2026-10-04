# Pipeline A — YouTube Long-Form (Horizontal)

## Purpose

Produce the full animated story as a horizontal (16:9) video for the main YouTube channel. This is the source-of-truth pipeline: it generates the complete story and every asset that Pipeline B later reuses. This page is the summary; each stage has its own detailed document.

## Output

- One finished long-form video, around 3–5 minutes, 16:9, English narration.
- A reusable asset bundle: approved story, approved script with speaker tags, scene breakdown, per-line narration audio, scene images/animations, and new character files.

## Human gates

1. **Gate A — story + cast** (Stage 1)
2. **Gate B — script** (Stage 1)
3. **Final review** (Stage 4)

Everything else is automated QA. After Gate B, Stages 2–4 run automatically until final review.

## Stage 0 — Topics, Story Queue & Management Page

- Local web page served by the Spring app. Topics are universes (description only); stories are concepts added under a topic.
- One channel-wide queue of stories; drag to reorder; Run starts the story at the top.
- Stories are editable only while `queued`. Concurrency capped by `max_concurrent_stories`.
- Detail: Stage 0 document.

## Stage 1 — Story & Script Generation

- **1A:** Claude expands the concept into a story and its cast — reusing the topic's characters, proposing new ones with a reason. QA checks structure, moral, and that new characters are distinct from the whole roster. **Gate A** approves story and cast; character files are created and the cast is locked.
- **1B:** Claude writes the script from the approved story, every line tagged with its speaker. QA checks length, story fidelity, cast lock, and tone. **Gate B** approves the script.
- Each gate: 5 automatic QA retries, 3 human rejections; either limit archives the story (restorable).
- Detail: Stage 1 document.

## Stage 2 — Narration / Audio

- Local open-source TTS (Kokoro, swappable). One fixed channel narrator; each character speaks with its assigned voice.
- QA: transcribe the audio back to text and compare with the script (about 95% match to pass); up to 3 automatic regenerations, then `needs_attention`.

## Stage 3 — Visual Generation

- Claude plans 5–10 second shots from the scene breakdown and narration timing; each shot gets one illustration (Flux 2 Klein 4B, local) using the characters' approved reference images and the channel style.
- MVP animation is storybook-style camera motion rendered with FFmpeg; AI video (Wan 2.2) is a later swap behind the same interface.
- QA (Claude vision): planned characters only, consistency with references, style, artifacts, safety, trademark resemblance; 3 retries per shot, then `needs_attention`.

## Stage 4 — Assembly

- Intro, shots and end card on one timeline; music from the curated licensed library picked by scene mood and ducked under speech; subtitles as a separate `.srt` file.
- QA: sync, duration, loudness, subtitles, music licenses, file integrity.
- **Final review** by a human. Rejection requires a written reason; an analysis step routes targeted fixes (shots, audio lines, music, script or story) and the video comes back for review. 3 rejections, then `needs_attention`.

## Stage 5 — Publish

- Claude generates search-optimized title, description with chapters, tags, and 3 thumbnail variants; QA checks limits and honesty.
- Semi-manual: a publish checklist guides the human upload in YouTube Studio — private first, clean copyright check, then publish. Audience setting: not made for kids (family audience).
- Pasting the video URL marks it published and triggers Pipeline B.

## Key Principles

- Status field is the single source of truth; every stage reads and writes it.
- Automated QA after every stage; a failure stops and flags the story rather than letting it flow downstream.
- Everything runs locally in the MVP; storage, voice engine and orchestrator sit behind interfaces so they can be swapped later.
- All assets are stored and reusable by Pipeline B.

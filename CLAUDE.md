# CLAUDE.md — Kids' / Family Animated Story Channel

This repo builds a local, mostly automated pipeline that turns story concepts into narrated, animated YouTube videos (Pipeline A) and vertical clips for Shorts / TikTok / Reels (Pipeline B).

The specs in `docs/` are the source of truth. Read `docs/00-overview.md` first, then the doc for the stage you are working on. Every stage doc has inputs, outputs, QA checks, a **Scenarios** table, a **Status transitions** table and a **Decisions** list. Treat the scenarios table as the acceptance-test list for that stage, and do not change a documented decision silently: if the code needs to deviate, say so and update the doc in the same change.

## Stack

- **Backend:** Java + Spring Boot. Orchestration, database, management page, all stage logic.
- **Local AI sidecar:** Python (FastAPI) for models that only exist in Python: Kokoro TTS, Whisper transcription, Flux 2 Klein 4B image generation. Runs natively, **not in Docker** (Docker on a Mac cannot use the Apple GPU).
- **Media:** FFmpeg, installed per machine.
- **LLM / vision:** Claude for story, script, shot planning, clip selection, metadata, rejection analysis and image QA, behind an `LlmEngine` interface. MVP: the locally installed Claude Code CLI run headlessly (`claude -p`, structured JSON output) on the owner's Claude plan; later: the Claude API, by config (`storyforge.llm.engine`). A usage limit flags the story with the reset time and resumes it automatically.
- **Database:** PostgreSQL, run locally with `docker compose up -d` (see `docs/foundations.md`).
- **Source control:** GitHub, for versioning only. No GitHub Actions, no scheduler.

## Non-negotiable architecture rules

1. **The `status` field is the single source of truth.** Every stage reads it to decide what to do and writes it when done. No hidden in-memory workflow state. This is what allows a later swap to a scheduler or Temporal.
2. **Deterministic orchestration, intelligent stages.** The flow is plain Spring code. AI calls are bounded steps inside a stage, never an agent deciding the flow.
3. **Everything external sits behind an interface,** with config selecting the implementation:
   - `AssetStorage` (MVP: local directory; later: Cloudflare R2)
   - `VoiceEngine` (MVP: Kokoro; later: Chatterbox)
   - `ImageEngine` (MVP: Flux 2 Klein 4B, quantized)
   - `AnimationEngine` (MVP: `StorybookAnimator` via FFmpeg; later: Wan 2.2 image-to-video)
   - `LlmEngine` (MVP: Claude Code CLI on the owner's plan; later: Claude API)
4. **Never store the same thing twice; never redo what passed.** Generated assets are cached by a hash of their inputs (text, voice/model, settings, engine version). Restarts and retries only regenerate what is missing or failed.
5. **A failure stops and flags.** QA failure past its retry budget, or an unrecoverable error, sets `needs_attention` (or `archived` where the doc says so). Nothing broken flows downstream.
6. **External API outages never count as strikes.** Back off and retry, then flag.
7. **Status changes that pick work (Run, clip queue) happen in one DB transaction,** so nothing starts twice.
8. **Log every attempt:** inputs, prompt version, model/engine version, raw output, each QA result, counters, cost, timestamps, and every status change with who/what triggered it and why.

## Repo hygiene

- Generated assets (audio, images, video) live in a configurable directory **outside the repo** or git-ignored. Never commit them.
- Secrets (API keys) come from environment variables or local config. Never commit them.
- **Prompt templates are versioned files in the repo**, so every output can be traced to the prompt version that made it.
- **Character files are versioned in the repo**, one per character (YAML), recurring or one-off. Their reference images live in the assets directory with keep-forever retention.
- All paths, limits, thresholds and model choices come from config, never hardcoded. Compute device is auto-detected (CUDA → Apple MPS → CPU) and overridable.

## Licensing rules (the channel is monetized)

- Only use models whose **weights** allow commercial use (Apache 2.0, MIT). Excluded examples: XTTS v2, Flux 2 dev, Fish Audio S2 Pro.
- Prompts **never name a studio, franchise or existing character**. The style is described by its ingredients (see the overview's Visual Style).
- Music only from the curated local library, each track stored with its license file. Reject non-commercial licenses at import.

## Status values

**Story (Pipeline A):**
`queued` → `story_in_progress` → `awaiting_story_approval` → `story_approved` → `script_in_progress` → `awaiting_script_approval` → `script_approved` → `audio_in_progress` → `audio_done` → `visuals_in_progress` → `visuals_done` → `assembling` → `awaiting_final_review` → (`analyzing_rejection`) → `final_approved` → `preparing_publish` → `ready_to_upload` → `published`
Side states: `needs_attention`, `archived`.

**Clip job / clip (Pipeline B):**
`clips_queued` → `selecting` → `rendering` → `formatting` → `clip_ready` → `clip_approved` → `clip_published`
Side states: `needs_attention`, `clip_killed`.

## Key config defaults

| Key | Default |
| --- | --- |
| `max_concurrent_stories` | 1 |
| Story / script QA retries | 5 each |
| Story / script / final human rejections | 3 each |
| Audio retries per line; transcript match | 3; ~95% |
| Duration tolerance (3–5 min target) | 2.5–6 min |
| Pauses: same speaker / speaker change / scene | 0.3 s / 0.6 s / 1.2 s |
| Shot length; max characters per shot | 5–10 s; 4 |
| Image retries per shot | 3 |
| Clips per story; clip slice length | 3 (min 1); 20–52 s, under 60 s with closing line |
| Clip rejections | 3 |
| Audience setting | not made for kids (family audience) |

## Build order

Build and test one stage at a time, in pipeline order, each against its scenarios table:

1. **Foundations** (`docs/foundations.md`, done): Spring project, DB schema (topics, stories, characters, attempts/logs), config, `AssetStorage` (local), status/transition service with transactional "pick next".
2. **Stage 0:** management page — topics, stories, queue with drag-to-reorder, Run, archive/restore, views per status.
3. **Stage 1:** story + cast (Gate A, with character reference sheets), script (Gate B), retry counters, character files. Needs the sidecar's image endpoint for reference sheets.
4. **Python sidecar** (`sidecar/`): image endpoint done (Flux 2 Klein, used for Stage 1 reference sheets); TTS and transcription come with Stage 2. Device auto-detection.
5. **Stage 2:** per-line audio, transcript QA, timing manifest, pronunciation dictionary.
6. **Stage 3:** shot planning, image generation + vision QA, storybook animation.
7. **Stage 4:** music library import, assembly, subtitles, final review, rejection analysis and routing.
8. **Stage 5:** metadata, thumbnails, publish checklist, mark-as-published.
9. **Pipeline B:** B0 → B4.

## Setup tasks before the first real run (human)

- Pick the narrator voice from the Kokoro samples.
- Record and approve the fixed clip closing line ("Watch the rest of the episode on our channel!").
- Curate 15–30 music tracks with license files and mood tags.
- Create the channel intro and end card.
- Re-check YouTube's "made for kids" guidance before launch.

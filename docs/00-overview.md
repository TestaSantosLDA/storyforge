# Kids' / Family Animated Story Channel — Overview

## Concept

- Original animated stories for children, with meaning (a moral or theme).
- Content is original — no copyrighted clips — so it can be monetized cleanly.
- Stories written to stretch slightly older (family-friendly, not strictly toddler), to allow fuller monetization where appropriate rather than always being capped by made-for-kids.

## Series Model: Topics, Stories, Characters

- A **topic** is a series or world (e.g. "Fox & Owl in Whisperwood"). It owns a cast of recurring characters.
- A **story** belongs to a topic. A human adds only the story concept; Claude expands the concept into a story and then a script.
- **Cast generation:** Claude creates the topic's cast from the topic description, and adds characters when a new story needs them.
- **Every character gets a character file**, including one-off characters a script introduces. The file holds name, personality, visual description, reference images, and assigned voice.
- The full character roster is checked whenever a new character is created, so no new character looks or sounds like an existing one.
- Character files are versioned in the repo (text); reference images live in the assets directory under keep-forever retention.
- A character's identifying features are large shapes and colours (fur, clothing, accessories), never tiny details, because the image model does not draw tiny details reliably.
- **One fixed narrator voice** across the whole channel, as its signature; characters get their own voices.
- **One queue of stories** across all topics; Run starts the story at the top.

## Goal

- A modest, sustainable monthly revenue stream.
- Volume-aware: kids'/family ad revenue per view can be lower, so the model relies on consistent output and multiple income routes (ads, sponsorships, merch/licensing later).

## Publishing Cadence

- 2–3 videos per week. Consistency matters more to the algorithm than raw volume. Sustainable beats burst.

## Video Length

- Main stories: around 3–5 minutes — enough for a full story with a moral, short enough to hold attention and control production cost.
- Short-form clips: under 60 seconds, treated as a separate track and discovery engine.

## Language

- Videos are narrated — spoken voiceover, not wordless animation. Narration carries the story so the visuals can be good-enough rather than flawless, and it is what the transcribe-and-diff QA check depends on.
- Narration is a swappable pipeline stage: the visuals are language-neutral, so a new language is just the same stage run again with a translated script and a different voice. One story, many audio tracks, eventually many videos off the same visuals.
- Additional languages are added over time as the channel grows. Cost per added language is small because the expensive visual work is already paid for.
- Script structure is kept language-neutral so translation slots in cleanly.

## Visual Style

One style for the whole channel: **classic storybook animation**, a vintage hand-drawn look with a soft palette.

- Hand-drawn characters with clean, slightly soft ink outlines and simple cel shading (one shadow tone, no gradients).
- Painted backgrounds with a watercolour/gouache feel, softer and less detailed than the characters.
- Muted palette: warm creams, sage greens, dusty blues, soft ochres, and muted reds (brick, rust, dusty rose). Never saturated or vivid colours.
- Rounded, expressive characters with big readable faces, in the mid-century feature-animation tradition.
- Light paper grain over everything.
- Stored once as a fixed style prompt block in channel config and added to every image generation.
- Guardrail: prompts never name a studio or existing franchise; QA flags any character that resembles a known trademarked character.

## Animation Approach

- MVP: **animated storybook**. Illustrated shots brought to life with camera motion (slow zooms, pans) and transitions, rendered locally with FFmpeg. Zero cost, runs on a 6 GB GPU (the dev machine has an 8 GB RTX 3060 Ti).
- Later: full AI video per shot (Wan 2.2, Apache 2.0), via a hosted API or on a stronger machine. The animation step sits behind an `AnimationEngine` interface, so switching is a config change.

## Platform & Account Structure

- One YouTube channel for both long videos and Shorts — Shorts feed discovery into the long-form. Do not split into two channels.
- Separate TikTok and Instagram accounts for the vertical clips.
- Same brand across all platforms.

## Two-Pipeline Architecture

- Pipeline A — YouTube (horizontal): produces the full long-form story in landscape. The source of truth; generates all reusable assets. (See the Pipeline A document.)
- Pipeline B — Short-form (vertical): produces Reels / Shorts / TikTok clips. Re-assembles from the source assets rather than cropping the finished video. (See the Pipeline B document.)

## Management Page (MVP)

- A local web page served by the Spring app itself — no GitHub Pages, no scheduler, no external hosting.
- On the page: create topics (universes), add story concepts under them, see every story's status, edit stories while still queued, and drag to reorder the single story queue.
- Triggering is manual: the Run button starts the first story in the queue. There is no daily batch job in the MVP.
- The same page hosts the human gates: story + cast approval, script approval, final review, the publish checklists, and the archive with restore.
- Full detail in the Stage 0 document.

## Notifications

- MVP: no notifications. The person triggering a story is at the page and sees its status there.
- Later, once stages run unattended (long visual generation, or a scheduled trigger), add notifications at human gates with a deep link to the item's approval screen — email, or a Telegram/Discord bot.

## Technical Architecture (MVP)

- Deterministic orchestration, intelligent stages: the pipeline backbone is a normal Spring Boot workflow. AI calls (script generation, clip selection) are bounded steps inside stages, not an agent running the whole flow.
- Orchestration = database + status field. Each story is a row; a manual trigger from the management page starts a stage, and each stage writes the new status when it finishes.
- The status field is the single source of truth. Every stage reads and writes it cleanly, so the orchestrator (or a future scheduler) can be swapped without touching stage logic.
- Retry and rejection counters are integer columns on the story row.
- Human gates are statuses that wait until a person acts on the management page.
- Not used in the MVP: Temporal (fits well, but operationally heavy for this volume), a daily batch job, and GitHub Actions. A scheduled trigger can be added later on top of the same status model.

* Chaining: once a script is approved, Stages 2–4 (audio, visuals, assembly) run automatically one after another until the final review gate. A failed stage stops the chain and flags the story.

### Hosting & storage (development phase)

- Everything runs locally during development: Spring app, database, and scheduled jobs on a developer machine. Goal is proving the full pipeline works end to end at zero infrastructure cost.
- Generated files (scripts, audio, scene images/animations, rendered videos) are saved to a configurable local directory, organised per story.
- Storage sits behind an interface (e.g. `AssetStorage`: save / load / delete). The local-directory implementation is the first one; moving to cloud storage later is a new implementation plus a config change, with no change to stage code.
- Planned cloud target when moving off local: Cloudflare R2 (S3-compatible API, 10 GB free tier, free egress). Backblaze B2 is the fallback.
- Retention policy, to stay within free tiers later: keep scripts, scene breakdowns, character sheets and logs forever; keep scene animations and narration until Pipeline B finishes; delete final renders after publishing, since the platforms hold them.

### Source control

- Code lives in a GitHub repository; GitHub is used for versioning only, not as a scheduler or runtime in the MVP.
- The generated-assets directory lives outside the repo (or is git-ignored), so videos and audio never get committed.
- API keys and other secrets stay in local config or environment variables, never in the repository.
- Prompt templates and the system prompt for script generation are versioned in the repo, so every generated script can be traced to the prompt version that produced it.

### Local AI sidecar & portability

- Voice (TTS) and transcription models are Python, so the Spring app calls a small local Python service (e.g. FastAPI) for them. Still fully local and free.
- Voice engine: Kokoro (Apache 2.0, runs on CPU) as the MVP default, behind a swappable `VoiceEngine` interface; Chatterbox (MIT, more expressive, GPU) is the planned upgrade. Non-commercial-licensed models (e.g. XTTS v2) are excluded because the channel is monetized.
- Hardware: current dev machine has an NVIDIA GPU; a MacBook (Apple Silicon) is planned. Migration should be clone, install, run.
- Portability rules: the compute device is auto-detected (CUDA → Apple MPS → CPU) and overridable in config; the Python sidecar runs natively, not in Docker, because Docker on a Mac cannot use the Apple GPU; dependencies are pinned with a lockfile; all paths (assets directory, model cache) come from config, never hardcoded; FFmpeg is installed per machine.

## Safety Principle (cross-cutting)

- Validation after every stage — human approval where judgment matters, automated QA everywhere else.
- Every stage logs input, output, and QA result, so failures are traceable.
- A failed gate stops the item and flags it rather than letting a bad video flow downstream.
- The queue only advances when the current item passes or is approved.

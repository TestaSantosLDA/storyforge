# Stage 3 — Visual Generation

Stage 3 turns the approved scenes and the narration timing into animated shots in the channel style. It runs automatically after audio, has no human gate, and checks every image for character consistency, style, and safety before animating it.

## Position in the pipeline

- Pipeline: A (long-form). Stage 2 (Narration / Audio) → **Stage 3** → Stage 4 (Assembly).
- Trigger: automatic when status becomes `audio_done`; status → `visuals_in_progress`.
- MVP approach: **animated storybook** — one illustration per shot, brought to life with camera motion. Full AI video is a later upgrade (see Upgrade path).

## Inputs

| Input | Source |
| --- | --- |
| Scene breakdown (scenes, visual descriptions, lines per scene) | Stage 1 |
| Timing manifest (start/end of every line) | Stage 2 |
| Character files: visual description and approved reference images | Character files (images created at Gate A) |
| Topic description snapshot (the setting) | Stage 0 |
| Channel style prompt block | Channel config |

## Outputs

- **Shot list**: every shot with its time range, characters, action, framing, and camera move.
- **One approved illustration per shot**, 16:9.
- **One animated clip per shot**, exactly as long as its time range in the manifest.
- All cached by a hash of their inputs, so a re-run only redoes what changed.

## Step 3A — Shot planning

- Claude splits each scene into shots at line boundaries, so no single image stays on screen too long (target 5–10 seconds per shot; config value).
- For each shot: characters present, what they are doing, framing (wide, medium, close-up), and camera move (slow zoom in or out, pan left or right, hold).
- Shot durations come from the timing manifest, so visuals follow the narration exactly.
- QA: shots cover the whole timeline with no gaps or overlaps; every shot uses only the story's approved cast; durations within the configured range.

## Step 3B — Image generation

- Engine: **Flux 2 Klein 4B** (Apache 2.0), transformer and text encoder quantized to 4-bit (NF4), 4 steps, behind an `ImageEngine` interface.
- Each image is generated from: the shot description + the channel style block + the setting + the reference images of every character in the shot + every character's full visual description.
- **Reference budget:** at most 4 reference images per shot, each downscaled to 512 px on its long side (config values). With 1–2 characters, each character gets front + side; with 3–4 characters, front only.
- **Action phrasing:** the prompt gives each character's action as its own sentence naming that character ("Hazel reads the book aloud. Bramble listens."), because who-does-what is the weakest point with two or more characters.
- Rendered at the model's 16:9 size, then upscaled to 1920×1080.
- Shots with more than 4 characters: the shot planner keeps at most 4 characters per shot.

### Automated QA — per image

Checked by a vision model (Claude) against the shot plan and reference images.

| Check | Pass condition |
| --- | --- |
| Characters | Exactly the planned characters are present; no extras, no duplicates of a character |
| Action | Each character does what the shot plan says (who does what is not swapped) |
| Consistency | Each character matches their reference images (shape, colours, clothing) |
| Style | Follows the channel style; muted palette, no saturated colours |
| Artifacts | No extra limbs, broken faces, melted objects, or garbled text |
| Safety | Family-appropriate, nothing frightening |
| Trademark | No resemblance to known trademarked characters |
| Format | 16:9, 1920×1080 after upscaling |

## Step 3C — Animation (storybook)

- Each approved image becomes a clip with its planned camera move, rendered locally with FFmpeg.
- Clip length matches the shot's time range exactly.
- Engine sits behind an `AnimationEngine` interface: `StorybookAnimator` in the MVP.

### Automated QA — per clip

| Check | Pass condition |
| --- | --- |
| Duration | Matches the shot's time range to within one frame |
| Format | 1920×1080, configured frame rate |
| Frames | No black or frozen-blank frames |
| Motion | Camera move stays inside the image (no empty edges) |

## Retry rules

- A failing image or clip is regenerated automatically, up to **3 times per shot**. An image retry is a fresh draw with a new seed; the failed check is never written into the image prompt, because naming a flaw ("not plaid") makes the image model draw it (seen in the 2026-10-05 reference-sheet run). Where a fix needs different wording, restate the wanted feature positively (e.g. "navy-and-white striped neckerchief").
- If a shot still fails, the story goes to `needs_attention` listing the failing shots. Passing shots are kept.
- Not archived: story, script and audio are already approved.

## needs\_attention — human options

| Situation | Options on the page |
| --- | --- |
| A shot keeps failing | Retry; edit that shot's description and retry; or accept the image anyway |
| A character keeps drifting from their references | Retry with stronger reference weighting; or regenerate that character's reference sheet (requires a quick re-approval) |
| Image engine out of memory or fails to load | Resume after freeing GPU memory or lowering the resolution setting |

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Shots planned, every image and clip passes QA; Stage 4 starts | `visuals_done` |
| 2 | Character looks different from their reference | Consistency failure; regenerate that shot (up to 3) | `visuals_in_progress` |
| 3 | Extra unplanned character appears, or a character is drawn twice | Characters check fails; regenerate | `visuals_in_progress` |
| 3b | Characters are right but their actions are swapped | Action check fails; regenerate with the actions restated | `visuals_in_progress` |
| 4 | Image looks like a known trademarked character | Trademark check fails; regenerate with a new seed (the resemblance is not named in the prompt) | `visuals_in_progress` |
| 5 | Colours come out too vivid | Style check fails; regenerate | `visuals_in_progress` |
| 6 | A shot fails 3 times | Flag with failing shots listed | `needs_attention` |
| 7 | Scene needs more than 4 characters together | Shot planner splits it into shots of at most 4 | `visuals_in_progress` |
| 8 | GPU runs out of memory | Retry once at lower settings; then flag (not counted as a shot attempt) | `needs_attention` |
| 9 | App restarts mid-stage | Cached passing images and clips are kept; only missing ones are generated | `visuals_in_progress` |
| 10 | Vision QA API unavailable | Backoff retries, not counted as attempts; then flag | `needs_attention` |
| 11 | Long render time | Expected (about 10–25 s per image measured); the stage runs unattended and the page shows progress per shot | `visuals_in_progress` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `audio_done` | Automatic | `visuals_in_progress` |
| `visuals_in_progress` | All shots pass | `visuals_done` → Stage 4 |
| `visuals_in_progress` | Shot fails 3 times, or engine/API failure | `needs_attention` |
| `needs_attention` | Retry, edited shot, or accept | `visuals_in_progress` |

## Upgrade path — AI video

- Swap `StorybookAnimator` for a Wan 2.2 image-to-video animator (Apache 2.0): via a hosted API (per-clip cost) or locally on a machine with enough memory.
- Steps 3A and 3B stay exactly the same: AI video animates the same approved images.
- A hybrid mode (AI video only for shots marked as key moments) is a config option on the same interface.

## Logging

- Per shot: plan, prompt, references used, seed, engine version, each attempt's QA results, render time.
- Per story: total shots, total render time, and every status change with its reason.

## Decisions

- Animated storybook for the MVP; AI video later behind the same interface.
- Flux 2 Klein 4B (Apache 2.0), NF4-quantized. Models with non-commercial licenses are excluded. Validated by the 2026-10-04 spike (`docs/spikes/2026-10-04-flux-consistency.md`): with references, 9/10 shots usable within 3 tries; peak about 4.7 GB VRAM with 4 references at 512 px, so it fits a 6 GB card.
- References are mandatory: without them, 3/10 spike shots went off-model.
- Fallback if consistency fails in production: a small LoRA per recurring character trained on the Klein 4B base weights, used together with references.
- Channel style: classic storybook animation, muted palette including soft reds; studios are never named in prompts.
- Character references are approved at Gate A; Stage 3 has no human gate.
- 3 automatic retries per shot, then `needs_attention`. Shots are 5–10 seconds, at most 4 characters each.

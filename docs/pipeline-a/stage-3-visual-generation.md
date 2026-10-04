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

- Engine: **Flux 2 Klein 4B** (Apache 2.0), quantized to fit the 6 GB GPU, behind an `ImageEngine` interface.
- Each image is generated from: the shot description + the channel style block + the setting + the reference images of every character in the shot (the model takes up to 4 references).
- Rendered at the model's 16:9 size, then upscaled to 1920×1080.
- Shots with more than 4 characters: the shot planner keeps at most 4 characters per shot.

### Automated QA — per image

Checked by a vision model (Claude) against the shot plan and reference images.

| Check | Pass condition |
| --- | --- |
| Characters | Exactly the planned characters are present; no extras |
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

- A failing image or clip is regenerated automatically, up to **3 times per shot**, with the failed check fed back into the prompt.
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
| 3 | Extra unplanned character appears | Characters check fails; regenerate | `visuals_in_progress` |
| 4 | Image looks like a known trademarked character | Trademark check fails; regenerate with the resemblance named as a negative | `visuals_in_progress` |
| 5 | Colours come out too vivid | Style check fails; regenerate | `visuals_in_progress` |
| 6 | A shot fails 3 times | Flag with failing shots listed | `needs_attention` |
| 7 | Scene needs more than 4 characters together | Shot planner splits it into shots of at most 4 | `visuals_in_progress` |
| 8 | GPU runs out of memory | Retry once at lower settings; then flag (not counted as a shot attempt) | `needs_attention` |
| 9 | App restarts mid-stage | Cached passing images and clips are kept; only missing ones are generated | `visuals_in_progress` |
| 10 | Vision QA API unavailable | Backoff retries, not counted as attempts; then flag | `needs_attention` |
| 11 | Long render time on the 6 GB GPU | Expected; the stage runs unattended and the page shows progress per shot | `visuals_in_progress` |

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
- Flux 2 Klein 4B (Apache 2.0), quantized for the 6 GB GPU. Models with non-commercial licenses are excluded.
- Channel style: classic storybook animation, muted palette including soft reds; studios are never named in prompts.
- Character references are approved at Gate A; Stage 3 has no human gate.
- 3 automatic retries per shot, then `needs_attention`. Shots are 5–10 seconds, at most 4 characters each.

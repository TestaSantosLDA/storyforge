# Pipeline D — Motion Pictures (vertical short stories)

## Purpose

Short, illustrated, moving stories made natively for YouTube Shorts (and, through B3, TikTok and Reels). A human creates a topic and gives it a **video profile** (explanatory, entertaining, historic, and so on); stories are concepts under that topic, exactly as in Pipeline A. Claude writes a 30–60 s script in the voice the profile asks for, the app narrates it, and then Claude writes each scene as animation code (the Claude Motion approach: moving text, maps, charts, icons and cut-out illustrated characters), which the app renders and assembles into a captioned 9:16 video.

Pipeline A stays as it is. Pipeline D reuses its stages and swaps three things: the length (one minute, not five), the frame (9:16, not 16:9) and the look and voice (set per topic by its profile, not one channel-wide style).

## How it differs from Pipelines A, B and C

| | A — Fables | B — Clips | C — Story Shorts | **D — Motion Pictures** |
| --- | --- | --- | --- | --- |
| Source | Topic + concept | A finished A video, or any video | Topic + concept | Topic + concept |
| Length / frame | 3–5 min, 16:9 | 20–60 s, 9:16 | Under 60 s, 9:16 | **30–60 s, 9:16** |
| Visuals | Flux images + camera motion | Re-uses A's shots | Text card + licensed footage, **no image model** | **Code-driven motion graphics, with Flux cut-outs where needed** |
| Look and voice | One channel style | Inherited | One card design | **Per topic, from its video profile** |
| Human gates | Story, script, final | Clip review | Story, final | **Script + cast, final** |

### Overlap with Pipeline C

C and D are both "short vertical story from a topic" and share most of their new parts. Whichever is built first builds these once, and the other reuses them:

- The `story.format` column and the dispatcher that runs steps by format (C adds `story_short`; D adds `motion_picture`).
- The vertical assembly profile: 1080×1920, platform safe zones, the Shorts loudness target, under-60 s QA.
- Word-by-word captions built from Whisper word timestamps (also used by "clips from any video", `docs/pipeline-b/clips-from-any-video.md`).
- Shorts-only metadata in Stage 5, and B3 per-platform variants applied directly.
- Later, a topic `channel` field for multiple accounts (C needs it; D does not yet, see Decisions).

The difference is only in the picture: C puts narration over a text card and stock footage; D animates every scene in code. A profile could later choose C's look as one of its visual styles, but this spec keeps them as separate formats so neither blocks the other.

## Video profiles

A profile is a versioned YAML file in the repo (`channel/video-profiles/<id>.yaml`), so every video can be traced to the profile version that made it, like prompt templates. A topic picks one profile and may override any field. The profile and its overrides are snapshotted onto the story at Run, like the topic description.

### Look notes (free text)

Next to the profile, the topic form has a **Look notes** text box for anything the settings do not cover, in plain words: "chalkboard look, white chalk lines on dark green", "every scene opens with a big number", "keep it calm, no fast whip pans", "use a lot of maps". A story can add its own look notes too (on the story form, next to the concept); they are added to the topic's notes for that story only.

- Both are snapshotted at Run with the profile, so later edits never change a story in progress.
- They are passed to Claude in scene planning (3A), illustration prompts (3B) and scene code (3C), after the profile, and the vision QA checks the frames against them.
- They are wishes, not overrides of the rules: licensing, family safety, safe zones, the no-text-in-images rule and the real-people rule always win. Notes that name a studio, franchise, existing character or living artist are refused when the topic or story is saved, with the reason shown.
- Final-review rejection notes about the look are offered as a one-click "add to the topic's look notes", so a fix carries over to the next stories.

| Field | Meaning | Example values |
| --- | --- | --- |
| `genre` | What kind of video | `explanatory`, `entertaining`, `historic`, `inspirational`, `mystery` |
| `tone` | How the narrator speaks and what the script aims for | "curious and clear", "playful, punchy jokes", "grave, documentary" |
| `facts` | Whether the script makes factual claims and how they are checked | `fiction`, `checked` (claims listed at the gate) |
| `structure` | Beat sheet Claude must follow | hook → 3 beats → payoff; question → answer → "so what" |
| `visual_style` | Palette, fonts and illustration style block for images (never names a studio, artist or franchise) | flat explainer, storybook (channel default), ink-and-wash, bold cartoon |
| `voices` | Narrator only, or narrator + character lines | `narrator_only`, `cast` |
| `narrator_voice` | Kokoro voice and speed | default: the channel narrator |
| `target_seconds` | Target length | 45 (allowed 30–58) |
| `beat_seconds` | How often something new happens on screen | 2–4 s (Shorts cut faster than A's 5–10 s shots) |
| `motion` | Motion language from the scene kit | `calm` (eases, slow reveals), `snappy` (overshoot, whip pans, word pops), `documentary` (map routes, slow push-ins) |
| `on_screen_text` | How much the scenes lean on text, maps and charts | heavy for explanatory, dates and places for historic, light for entertaining |
| `music` | Background music on or off | **on** (default for every profile) |
| `music_mood` | Mood tags for the curated music library | "light curious", "tense", "warm" |
| `audience` | YouTube audience setting | not made for kids (family audience) |
| `look_notes` | Free text about how the videos should look (see below) | empty |

Starter profiles shipped with the spec:

| Profile | Genre | Facts | Voices | Look | Text cards |
| --- | --- | --- | --- | --- | --- |
| `explainer` | explanatory | checked | narrator only | flat explainer illustration | yes |
| `tale` | entertaining | fiction | cast | storybook | no |
| `chronicle` | historic | checked | narrator only | ink-and-wash | dates and places |

### Rules that come with genres

- **Facts.** With `facts: checked`, Claude returns every factual claim in the script as a numbered list with a short source note and a confidence. QA fails a script with a low-confidence claim; Gate A shows the list so the human can check it before anything is drawn. Invented quotes from real people are never allowed.
- **Real people (historic).** The narration may name real historical figures. Images never try to show a recognisable likeness of a real person: they are drawn as generic figures, from behind, in silhouette or at a distance, and image QA checks this. No living people.
- **Text inside images.** The image model cannot write reliably, so no illustration asks for words, labels or numbers. All text, dates, labels and numbers are drawn by the scene code.

## Status flow

Uses the existing story statuses and skips the script gate:

`queued` → `story_in_progress` → `awaiting_story_approval` → `story_approved` → `audio_in_progress` → `audio_done` → `visuals_in_progress` → `visuals_done` → `assembling` → `awaiting_final_review` → (`analyzing_rejection`) → `final_approved` → `preparing_publish` → `ready_to_upload` → `published`
Side states: `needs_attention`, `archived`.

The dispatcher picks the step by status and `format`; no new status values are needed.

## D1 — Script + cast (one gate)

At one minute, a separate story outline and script gate cost more review time than they save. Claude writes the finished script directly.

- Input: the topic, the profile snapshot, the look notes, the concept, optional notes, the topic's characters (for `voices: cast`).
- Output: a hook title, the speaker-tagged script (110–150 words at the narrator's pace, from `target_seconds`), the beats it follows, the cast (as in Stage 1A, reusing the topic's characters; new ones need a reason) and, with `facts: checked`, the claims list.
- **The first line is the hook** and must stand alone within about 3 s.
- Automated QA (LLM, 5 retries as in Stage 1): word count fits `target_seconds`; the first line works as a hook; it follows the profile's structure and tone; it has a payoff; claims are listed and high-confidence where required; no invented quotes; no living people; family-safe; no studio, franchise or existing character named; new characters distinct from the roster.
- **Gate A** shows the script, cast (with reference sheets, Stage 1 rules), claims list and profile. Approve, reject with notes (3 rejections archive), or archive.
- New prompt files: `motion-script-v1.md`, `motion-script-qa-v1.md`; the profile is injected as a block, not a separate prompt per genre.

## D2 — Narration

- Stage 2 as written: Kokoro, the profile's narrator voice and speed, characters' voices when `voices: cast`, Whisper transcript QA, pronunciation dictionary, caching.
- Pauses scaled down for short-form pacing (config: 0.15 / 0.3 / 0.5 s).
- Whisper word timestamps are kept with the timing manifest for captions (shared with C).
- Narration over 58 s flags the story: accept anyway, or send back to D1 to shorten.

## D3 — Motion scenes (vertical)

### Approach: code-driven motion graphics, like Claude Motion

Claude Motion (Anthropic, beta since 2026-10-08) makes short animated explainers by having Claude **write code** that moves text, shapes, charts and images; it uses no video-generation model and exports MP4. Pipeline D does the same thing inside StoryForge: Claude writes each scene as animation code, and the app renders it to frames locally. Claude Motion itself cannot be the engine: it is a chat feature on Team and Enterprise plans only, with no API or `claude -p` access, no published aspect ratios and no audio, so it cannot run unattended in the pipeline. Its approach is what we copy.

Why this fits better than AI video on an RTX 3060 Ti 8 GB:

- Motion is exact and repeatable: text, maps, timelines, charts and cut-out characters move exactly as written, never drift or melt (the Tamsin and Wan problems).
- The render runs in headless Chromium on the CPU, so the GPU is only used for the few illustrations.
- A rejection like "slow down the second scene" changes one scene's code, not a re-generation lottery.
- It suits the profiles: explanatory and historic videos are mostly diagrams, maps, dates and key terms; entertaining ones use illustrated cut-out characters with bouncy, cartoon motion.

### The scene kit

A small, versioned library in the repo (`motion-kit/`, HTML + CSS + JS, no network access at render time) that every scene builds on, like the prompt templates:

| Part | What it gives Claude |
| --- | --- |
| Stage | A 1080×1920 canvas with the platform safe zones marked, and the profile's fonts and palette as CSS variables |
| Time contract | Every scene exports `duration` and `seek(t)`, which draws the exact frame for time `t`. No timers, no `requestAnimationFrame`, no randomness without a fixed seed, so a frame always renders the same |
| Components | Kinetic text (word pops, highlights, counters), lower-thirds and date cards, timeline, map with route lines and pins (from bundled public-domain map outlines), bar/line/pie charts, icons, arrows and callouts, image layers with masks, and cut-out characters with simple rigs (bob, squash and stretch, blink, turn, walk cycle by sliding and bouncing) |
| Easing and transitions | A fixed set (ease, overshoot, spring, whip pan, wipe, match cut) so every video shares one motion language per profile |
| Fonts and icons | Bundled files with licenses that allow commercial use (e.g. SIL Open Font License fonts, MIT icon sets), stored with their license files like music |

The kit is versioned and its version goes into every scene's cache hash, so a kit change re-renders only what it affects.

### Step 3A — Scene planning

- Claude splits the script into scenes at line boundaries using the timing manifest, with the profile's `beat_seconds` (default 2–4 s per visual beat; a scene may hold several beats).
- For each scene: what is on screen, which kit components it uses, which illustrations it needs (if any), and what moves on which word (for example "the number counts up to 1,200 as the narrator says it").
- QA: scenes cover the timeline with no gaps; only the approved cast; every needed illustration is listed; nothing important outside the safe zone.

### Step 3B — Illustrations (only where the scene needs one)

- Characters, props and backgrounds the code cannot draw well are made with Flux 2 Klein 4B as in Stage 3B, with the profile's `visual_style`, the look notes and the character references.
- Characters are drawn on a plain background and cut out with a background-removal model (licence to confirm in the spike; only Apache 2.0 / MIT weights) into transparent PNG layers that the kit can move and rig.
- Image QA as Stage 3B, plus: no text in the image; with `genre: historic`, no recognisable real person.
- Explainer profiles may need no illustrations at all; a tale needs its cast as cut-outs plus a few backgrounds. Images are cached and reused across a topic's stories (a recurring character's cut-out poses are drawn once).

### Step 3C — Scene code

- Claude (`claude -p`, prompt `motion-scene-v1.md`) writes one scene file per scene against the kit, given the scene plan, the word timestamps for its lines, the profile, the look notes, and the illustration files.
- Static checks before rendering: only kit imports, no network or file access, `duration` matches the scene's time range, `seek(t)` exists, file under a size limit.
- Render: `MotionRenderer` (behind `AnimationEngine`, whose input widens from one image to a scene file) opens the scene in headless Chromium (Playwright), calls `seek(t)` for every frame at the configured frame rate, screenshots it, and pipes the frames to FFmpeg. About 45 s × 30 fps = 1,350 frames per short, on the CPU.

### Automated QA — per scene

| Check | Pass condition |
| --- | --- |
| Duration / format | Exactly the scene's time range, 1080×1920, configured frame rate |
| Determinism | Two renders of the same sampled frames are identical |
| Errors | No JavaScript errors or missing assets during the render |
| Layout (code) | No text outside the safe zone, no text overlapping other text, text above a minimum size |
| Look (Claude vision on start, middle and end frames) | Matches the plan, readable, on-style, family-safe, nothing broken or empty, no trademarked character |
| Motion present | Measured frame difference above a threshold across the scene (no static slides) |
| Sync | Each animated beat tied to a word starts within 0.2 s of that word |

A failing scene is rewritten with the failed checks given to Claude (unlike image prompts, code can be told what is wrong), up to 3 times, then `needs_attention` listing the scene. Passing scenes are kept.

### Contact sheet

D3 writes a contact sheet (start, middle and end frame of every scene) to the story's assets, and the final review screen shows it next to the video. This comes from the Tamsin lesson: nothing is delivered before its frames have been looked at.

### Later: AI video for a few shots

If a profile ever needs real camera footage feel, the 2026-10-19 options still apply behind the same `AnimationEngine` (depth parallax with Depth Anything V2 Small, Wan 2.2 14B Lightning for a few hero shots, hosted Wan as a paid fallback). They are not part of this spec's MVP.

## D4 — Assembly (vertical)

- Rendered scenes on one 1080×1920 timeline, cut on the frame grid from the timing manifest (Stage 3 rule).
- Word captions (shared with C), unless the scene already shows the words as kinetic text; the scenes carry all other on-screen text.
- **Background music is on by default** (owner, 2026-10-30): a track from the curated library picked by `music_mood`, never the same track twice in a row, looped or trimmed to length, faded in and out, and ducked under the voice (sidechain compression; about 17 dB under the voice between lines and lower while speaking; set from the owner's listening in the first motion test, where 9 and 11 dB were too loud). The mix is normalised to −17.5 LUFS (config), which the owner preferred to −14 LUFS; platforms turn louder videos down but never turn quieter ones up. A profile or story can turn music off.
- If no licensed track matches the mood, the story is flagged before assembly (`needs_attention`), as in Pipeline C; it never ships silent by accident. The first motion test used a short ambient bed synthesised from scratch by a script, which is a possible later source for the library.
- No intro and no end card (they waste the first seconds of a Short); the channel's watermark sits in a safe corner.
- QA: Stage 4's sync, loudness, license and integrity rows, plus length under 60 s, captions and text inside safe zones, every caption word in the transcript.
- **Final review**: Stage 4's screen with the video, contact sheet, script, claims list and licenses. Rejection reasons route through rejection analysis to D1 (script), D2 (a line), D3 (named scenes, rewritten with the note; other scenes and illustrations reused) or D4 (captions, music); 3 final rejections flag the story.

## D5 — Publish

- Stage 5 with Shorts metadata: title (≤ 100 characters, hook-led), description with hashtags, no chapters. For `facts: checked`, the description lists sources from the claims list.
- No thumbnail variants (Shorts use a frame); the human picks the cover frame in the checklist.
- B3 builds the TikTok and Reels variants directly; no B1 selection.
- Publishing stays manual: the pipeline ends at `ready_to_upload`, then the pasted URL marks it `published`.

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path (`explainer` profile) | Script + claims approved, narrated, 9 scenes written and rendered with no illustrations, assembled, reviewed, metadata ready | `ready_to_upload` |
| 1b | Happy path (`tale` profile) | As above, with the cast drawn once as cut-outs and rigged in the scenes | `ready_to_upload` |
| 2 | Topic created with the `chronicle` profile and a voice override | Profile and override snapshotted at Run; later profile edits do not change the story | unchanged |
| 2b | Topic look notes say "chalkboard look"; a story adds "open on a big number" | Both reach scene planning and scene code; vision QA checks frames against them | unchanged |
| 2c | Look notes name a film studio | Refused when the topic is saved, with the reason | unchanged |
| 3 | Script over 150 words | QA fails, retried with the reason | `story_in_progress` |
| 4 | Explanatory script has a low-confidence claim | QA fails, retried | `story_in_progress` |
| 5 | Historic script quotes a real person | QA fails (no invented quotes), retried | `story_in_progress` |
| 6 | Script QA fails 5 times | Flag with reasons | `needs_attention` |
| 7 | Reviewer rejects the script at Gate A | Regenerated with notes; rejection counter +1; third time archives | `story_in_progress` / `archived` |
| 8 | Narration over 58 s | Flag: accept or send back to D1 | `needs_attention` |
| 9 | Image has a recognisable real person (historic) | Image QA fails, fresh seed | `visuals_in_progress` |
| 10 | Image contains garbled text | Image QA fails, fresh seed | `visuals_in_progress` |
| 11 | Scene code uses a timer or fetches a URL | Static check fails; rewritten with the reason | `visuals_in_progress` |
| 12 | Scene throws a JavaScript error mid-render | Error check fails; rewritten with the error | `visuals_in_progress` |
| 13 | Kinetic text runs into the caption zone | Layout check fails; rewritten | `visuals_in_progress` |
| 13b | Scene is a static slide | Motion check fails; rewritten | `visuals_in_progress` |
| 14 | An image or a scene fails 3 times | Flag with the failing items | `needs_attention` |
| 15 | Final review: "slow down the second scene" | Routed to D3; only that scene rewritten and re-rendered | `visuals_in_progress` |
| 16 | Final review: "captions cover the face" | Routed to D4; everything else reused | `assembling` |
| 17 | Scene kit updated | Only scenes whose hash includes the old kit version are re-rendered on the next run | unchanged |
| 17b | No library track matches the profile's music mood | Flagged before assembly, with the mood named | `needs_attention` |
| 18 | `fable`, `story_short` and `motion_picture` stories in one queue | Each runs its own steps by `format`; one at a time | unchanged |
| 19 | Sidecar down | Backoff, not a strike; then flag | `needs_attention` |
| 20 | App restarts mid-D3 | Cached illustrations, scene code and rendered scenes kept; only missing ones made | `visuals_in_progress` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `story_approved` (`motion_picture`) | Automatic | `audio_in_progress` |
| `audio_done` (`motion_picture`) | Automatic | `visuals_in_progress` (D3) |
| `visuals_in_progress` | Every scene rendered and passing | `visuals_done` |
| `visuals_in_progress` | An image or scene fails 3 times, or engine/API failure past backoff | `needs_attention` |
| `visuals_done` (`motion_picture`) | Automatic | `assembling` (D4) |
| `awaiting_final_review` | Rejected with reason | `analyzing_rejection` → `story_in_progress`, `audio_in_progress`, `visuals_in_progress` or `assembling` |
| All others | As in Stages 1, 2, 4 and 5 | — |

## Data changes

- `story.format`: adds `motion_picture` (shared column with C).
- `topic.profile_id`, `topic.profile_overrides` (JSON), `topic.look_notes` (text).
- `story.look_notes` (text, optional).
- `story.profile_snapshot` (JSON, set at Run, with the profile file's version).
- Scene rows: plan, code path, kit version, render path, QA results (Stage 3's shot table, with a `kind` column, may hold them).

## Decisions (defaults, open to change)

- Same repo, app, database and queue as Pipelines A and C; told apart by `story.format`.
- **One human gate before production** (script + cast + claims), not two, because the script is one minute long.
- **Profiles are versioned YAML files**, picked per topic with overrides; three starters: `explainer`, `tale`, `chronicle` (confirmed by the owner, 2026-10-30), plus free-text look notes on the topic and the story.
- **Motion = code-driven motion graphics, the Claude Motion approach:** Claude writes each scene against a versioned scene kit, rendered deterministically in headless Chromium and FFmpeg. Claude Motion itself is not used (no API, Team/Enterprise only, no audio).
- Flux illustrations only where a scene needs them, as cut-out layers; the GPU is not on the critical path.
- AI video (depth parallax, Wan 2.2) is out of the MVP and stays a later option behind `AnimationEngine`. Only Apache 2.0 / MIT weights, fonts and icons with commercial-use licenses.
- Own scene kit rather than Remotion, because Remotion needs a paid company license above three people; Remotion stays an option if the kit proves too slow to build.
- Real people are named in narration only, never drawn recognisably; no living people; no invented quotes.
- No intro or end card; watermark only.
- Motion Pictures publish to the **same YouTube channel as the fables**, as Shorts (owner, 2026-10-30). Multiple channels and accounts are handled later; until then there is no per-topic channel setting.

## Build order

1. **Motion spike first (minimal kit, no pipeline code):** Claude writes one 45 s vertical short for `explainer` and one for `tale` against a first cut of the kit, rendered with Playwright + FFmpeg over a Kokoro narration. Measure render time, make a contact sheet, and have the owner judge both. If they do not pass, the approach is revised before anything below is built.
2. Shared vertical layer with C, if C has not built it yet: `format` column and dispatcher, vertical assembly profile, word captions.
3. Profiles: YAML files, topic fields, snapshot at Run, management page form.
4. D1 prompts and QA, single gate.
5. D2 short-form pauses and word timestamps.
6. D3: scene kit, scene planning, cut-out illustrations, scene code prompt and static checks, `MotionRenderer`, scene QA, contact sheet.
7. D4 assembly, final review routing.
8. D5 Shorts metadata and B3 variants.

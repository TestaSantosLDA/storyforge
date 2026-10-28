# Pipeline C — Story Channel (spin-off)

## Purpose

A second, cheaper format that runs on the same app. Original stories (dilemmas, workplace drama, twists) are narrated over a branded text card and looping background footage, with big word-by-word captions. No image or video model is involved, so it can ship while Pipeline A's visuals are improved.

Like Pipeline A, each story becomes one **long-form video** (3–5 min, 16:9). **Pipeline B then cuts it into 2–3 Shorts / TikToks / Reels** that point back to the full story. Pipeline A stays as it is; nothing in it is removed or changed by this pipeline.

## Why it reuses so much

A Pipeline C story is a Pipeline A story with the visual stages swapped for a text-card renderer:

| Piece | Reused from | Change |
| --- | --- | --- |
| Topics, story rows, queue, Run, archive, management page | Foundations, Stage 0 | New `format` column on the story: `fable` (default) or `story_card` |
| Status field, transactional pick-next, counters, attempt and status logs | Foundations | None |
| `LlmEngine` (Claude Code CLI), prompt library, auto-resume after usage limits | Stage 1 | New prompt files `card-story-v1.md`, `card-story-qa-v1.md` |
| Gate A (approve / reject with notes / archive) | Stage 1 | No cast step and no character files |
| Narration: Kokoro, Whisper transcript QA, pronunciation dictionary, timing manifest, caching | Stage 2 | One narrator only; Whisper word timestamps saved for captions |
| Final review, rejection analysis and routing | Stage 4 | Routes to C1 (story) or C3 (assembly) |
| Metadata, thumbnail, publish checklist, mark as published | Stage 5 | Thumbnail is the story card |
| **Shorts: clip queue, clip selection, vertical render, per-platform formatting, publish** | **Pipeline B (B0–B4)** | B2 uses the text-card renderer instead of re-generating images (see below) |
| **New:** card renderer, caption builder, background library, text-card assembly | — | This document; shared by C3 and B2 |

Script (Gate B), shot planning, image generation and animation are skipped.

## Status flow

Uses the existing story statuses and skips the ones it does not need:

`queued` → `story_in_progress` → `awaiting_story_approval` → `story_approved` → `audio_in_progress` → `audio_done` → `assembling` → `awaiting_final_review` → (`analyzing_rejection`) → `final_approved` → `preparing_publish` → `ready_to_upload` → `published` → Pipeline B starts (`clips_queued` …)
Side states: `needs_attention`, `archived`.

The stage dispatcher picks the step by status and `format`; no new status values are needed.

## C1 — Story

- Input: topic (the series, e.g. "Office stories"), a concept from a human or "surprise me", optional notes.
- Claude writes an **original** first-person story of 450–750 words (3–5 min at the narrator's pace). It is split into numbered lines (one or two sentences each, all spoken by the narrator), so Stage 2 and Pipeline B can work line by line as they do for fables.
- It also writes a title of up to 70 characters for the card and thumbnail.
- The story has 2–3 strong moments that work on their own, so B1 can find clips without spoiling the ending.
- Automated QA (LLM, 5 retries as in Stage 1):
  - word count in range;
  - the first line works as a hook;
  - there is a clear payoff or twist;
  - no real people, brands or companies;
  - it does not retell a known viral post;
  - it passes the content policy in config (default: no slurs, no graphic violence, no sexual content).
- Gate A: approve, reject with notes (3 rejections archive the story), or archive.

## C2 — Narration

- Stage 2 as written, with one narrator voice taken from the topic (default: the channel narrator), and shorter pauses (config) for a faster pace.
- Whisper word-level timestamps are saved with the timing manifest, so captions match what is actually spoken.

## C3 — Assembly (long-form, 1920×1080)

- **Card:** the title on the channel's own card design (avatar, channel name, title). It is rendered once per story as a PNG from a versioned template in the repo and shown during the first line. It never imitates Reddit or any other site's interface.
- **Captions:** 1–3 words at a time, large, centred, with the current word highlighted. They are built as an ASS subtitle file from the word timestamps and burned in with FFmpeg.
- **Background:** clips from the background library, chained to the narration length, never the same clip twice in a row.
- **Audio:** narration on top, an optional quiet music bed from the existing music library (ducked), normalized to the YouTube loudness target.
- QA:
  - length within the duration tolerance;
  - every caption word appears in the narration transcript;
  - loudness on target;
  - no frozen or black frames.

## Shorts through Pipeline B

Pipeline B runs unchanged for a published `story_card` story, except where noted:

| B stage | For `story_card` stories |
| --- | --- |
| B0 Clip queue | Same trigger (story published). No GPU lock needed, so jobs never wait for Pipeline A. |
| B1 Clip selection | Unchanged: 20–52 s slices on line boundaries, strong opening, self-contained, no ending spoiler, hook title. |
| B2 Vertical re-assembly | Steps 1–2 (vertical images, animation) are replaced by the text-card renderer at 1080×1920: hook-title card, background clip, word captions inside the safe zone. Image QA rows don't apply. Steps 3–7 (original narration, closing line, music, captions, render) are unchanged. |
| B3 Per-platform formatting | Unchanged; the call to action points to the full story. Cover is the hook-title card. |
| B4 Publish | Unchanged. |

The closing line is set per channel ("Watch the full story on our channel!" here), recorded once in this channel's narrator voice.

## Background library

- Curated by a human, like the music library: each clip is stored with its license file and imported only if the license allows commercial use on YouTube, TikTok and Instagram.
- Good sources are footage you record yourself, or stock libraries whose license allows commercial use. Game footage is accepted only with the publisher's written permission for monetized video, recorded in the license file.

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Story approved, narrated, assembled, reviewed, published; Pipeline B makes the Shorts | `published`, then `clips_queued` |
| 2 | Story over 750 words | QA fails; retried with the reason | `story_in_progress` |
| 3 | Story names a real company | QA fails; retried | `story_in_progress` |
| 4 | Story QA fails 5 times | Flag with the reasons | `needs_attention` |
| 5 | Reviewer rejects the story with notes | Regenerated with notes; rejection counter +1 | `story_in_progress` |
| 6 | Third story rejection | Archived | `archived` |
| 7 | Narration outside the duration tolerance | Flag: accept anyway or send back to C1 | `needs_attention` |
| 8 | Background library empty | Flag before assembly | `needs_attention` |
| 9 | Caption word missing from the transcript | Captions rebuilt from the transcript; if still wrong, flag | `needs_attention` |
| 10 | Final review rejected: "captions too small" | Routed to C3 with the note; story and audio reused | `assembling` |
| 11 | A `fable` story and a `story_card` story in the queue | Each runs its own steps by `format`; one queue, one at a time | unchanged |
| 12 | `story_card` clip job while Pipeline A holds the GPU | Runs anyway; it needs no GPU | `selecting` |
| 13 | Short rejected in B4: "captions cover the subscribe button" | Routed to B2; only the captions are redone | `rendering` |
| 14 | Sidecar down | Backoff, not counted as a strike; then flag | `needs_attention` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `story_approved` (`story_card`) | Automatic | `audio_in_progress` |
| `audio_done` (`story_card`) | Automatic | `assembling` |
| `assembling` | QA passes | `awaiting_final_review` |
| `assembling` | QA fails after retries | `needs_attention` |
| `published` (`story_card`) | Automatic | clip job `clips_queued` (B0) |
| All others | As in Stages 1, 2, 4 and 5 and Pipeline B | — |

## Decisions (defaults, open to change)

- Same repository, app, database and queue as Pipeline A; formats are told apart by `story.format`.
- Each story makes one long-form video plus 2–3 Shorts through Pipeline B, mirroring the fable channel's model.
- Stories are **original**, written by Claude and approved by a human. Real Reddit (or other) posts are never copied, and no site's interface is imitated.
- This is a separate channel and brand from the family fable channel, because the tone and audience differ. The topic records which channel a story belongs to.
- No image, video or animation model is used in this pipeline.

## Build order

1. `format` column and dispatcher routing (tests show a fable story is unaffected).
2. C1 prompts and QA.
3. Stage 2 lands on main, then the C2 word-timestamp change.
4. Card renderer, caption builder, background library import, C3 assembly.
5. Final review and Stage 5 wiring.
6. Pipeline B with the text-card variant of B2 (Pipeline B is not built yet for fables either, so C can be its first user).

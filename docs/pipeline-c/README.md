# Pipeline C — Story Shorts (spin-off)

## Purpose

A second, cheaper format that runs on the same app: original short stories (dilemmas, workplace drama, twists) narrated over a branded text card and a looping background, with big word-by-word captions. No image or video model is involved, so it can ship while Pipeline A's visuals are improved. Pipeline A stays as it is; nothing in it is removed or changed by this pipeline.

## Why it reuses so much

A story short is a Pipeline A story with the visual stages swapped out:

| Piece | Reused from | Change |
| --- | --- | --- |
| Topics, story rows, queue, Run, archive, management page | Foundations, Stage 0 | New `format` column on the story: `fable` (default) or `story_short` |
| Status field, transactional pick-next, counters, attempt and status logs | Foundations | None |
| `LlmEngine` (Claude Code CLI), prompt library, auto-resume after usage limits | Stage 1 | New prompt files `short-story-v1.md`, `short-story-qa-v1.md` |
| Gate A (approve / reject with notes / archive) | Stage 1 | No cast step and no character files |
| Narration: Kokoro, Whisper transcript QA, pronunciation dictionary, caching | Stage 2 | One narrator only; Whisper word timestamps kept for captions |
| Final review screen, rejection reasons and routing | Stage 4 | Routes only to story or assembly |
| Metadata, publish checklist, mark as published | Stage 5 | Shorts-only metadata (title, description, hashtags) |
| Per-platform variants | Pipeline B · B3 | Applied directly; no clip selection needed |
| **New:** card renderer, caption builder, background library, vertical assembly | — | This document |

Script (Gate B), shot planning, image generation, animation and Pipeline B clip selection are skipped.

## Status flow

Uses the existing story statuses and skips the ones it does not need:

`queued` → `story_in_progress` → `awaiting_story_approval` → `story_approved` → `audio_in_progress` → `audio_done` → `assembling` → `awaiting_final_review` → `final_approved` → `preparing_publish` → `ready_to_upload` → `published`
Side states: `needs_attention`, `archived`.

The stage dispatcher picks the step by status and `format`; no new status values are needed.

## C1 — Story

- Input: topic (the series, e.g. "Office stories"), concept from a human or "surprise me", optional notes.
- Claude writes an **original** first-person story of 120–150 words (about 45–55 s at the narrator's pace), plus a hook title of up to 70 characters for the card.
- The first sentence is the hook and must stand alone within about 3 s.
- Automated QA (LLM, 5 retries as in Stage 1): word count in range; the first sentence works as a hook; it has a clear payoff or twist; no real people, brands or companies; it does not retell a known viral post; it passes the content policy in config (default: no slurs, no graphic violence, no sexual content).
- Gate A: approve, reject with notes (3 rejections archive the story), or archive.

## C2 — Narration

- Stage 2 as written, with one narrator voice taken from the topic (default: the channel narrator), and pauses scaled down for short-form pacing.
- Whisper word-level timestamps are saved with the timing manifest, so captions match what is actually spoken.

## C3 — Assembly (vertical, 1080×1920)

- **Card:** the hook title on the channel's own card design (avatar, channel name, title). It is rendered once per story as a PNG from a versioned template in the repo, and shown for the first sentence. It never imitates Reddit or any other site's interface.
- **Captions:** 1–3 words at a time, large, centred in the safe zone, current word highlighted, built as an ASS subtitle file from the word timestamps and burned in with FFmpeg.
- **Background:** a random clip from the background library, cut to the narration length; not the same clip twice in a row.
- **Audio:** narration on top, an optional quiet music bed from the existing music library (ducked), normalized to the Shorts loudness target.
- QA: length under 60 s; every caption word appears in the narration transcript; captions and card inside the platform safe zones; loudness on target; no frozen frames.

## Background library

- Curated by a human, like the music library: each clip stored with its license file, and imported only if the license allows commercial use on YouTube, TikTok and Instagram.
- Good sources: footage you record yourself, or stock libraries whose license allows commercial use. Game footage is accepted only with the publisher's written permission for monetized video, recorded in the license file.

## Final review and publish

- Stage 4's review screen shows the video, the story text and the background license. Rejection reasons route to C1 (story) or C3 (assembly); 3 final rejections archive the story.
- Stage 5 builds the metadata. Publishing stays manual: the pipeline ends at `ready_to_upload`.

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Story approved, narrated, assembled, reviewed; metadata ready | `ready_to_upload` |
| 2 | Story over 150 words | QA fails, retried with the reason | `story_in_progress` |
| 3 | Story names a real company | QA fails, retried | `story_in_progress` |
| 4 | Story QA fails 5 times | Flag with the reasons | `needs_attention` |
| 5 | Reviewer rejects the story with notes | Regenerated with notes; rejection counter +1 | `story_in_progress` |
| 6 | Third story rejection | Archived | `archived` |
| 7 | Narration over 58 s | Flag: accept anyway or send back to C1 to shorten | `needs_attention` |
| 8 | Background library empty | Flag before assembly | `needs_attention` |
| 9 | Caption word missing from transcript | Captions rebuilt from the transcript; if still wrong, flag | `needs_attention` |
| 10 | Final review rejected: "captions too small" | Routed to C3 with the note; story and audio reused | `assembling` |
| 11 | A `fable` story is in the queue next to shorts | Each runs its own steps by `format`; one queue, one at a time | unchanged |
| 12 | Sidecar down | Backoff, not counted as a strike; then flag | `needs_attention` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `story_approved` (`story_short`) | Automatic | `audio_in_progress` |
| `audio_done` (`story_short`) | Automatic | `assembling` |
| `assembling` | QA passes | `awaiting_final_review` |
| `assembling` | QA fails after retries | `needs_attention` |
| `awaiting_final_review` | Rejected with reason | `story_in_progress` or `assembling` |
| All others | As in Stages 1, 2, 4 and 5 | — |

## Decisions (defaults, open to change)

- Same repository, app, database and queue as Pipeline A; formats are told apart by `story.format`.
- Stories are **original**, written by Claude and approved by a human. Real Reddit (or other) posts are never copied, and no site's interface is imitated.
- Separate channel and brand from the family fable channel, because the tone and audience differ. The topic records which channel a story belongs to.
- The output is vertical-only and under 60 s; there is no long-form version.
- No image, video or animation model is used in this pipeline.

## Build order

1. `format` column and dispatcher routing (with tests showing a fable story is unaffected).
2. C1 prompts and QA.
3. Stage 2 lands on main, then the C2 word-timestamp change.
4. Card renderer, caption builder, background library import, C3 assembly.
5. Final review and Stage 5 wiring.

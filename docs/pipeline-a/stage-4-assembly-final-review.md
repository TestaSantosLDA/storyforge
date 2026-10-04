# Stage 4 — Assembly & Final Review

Stage 4 stitches narration, animated shots, music, intro and end card into the finished video, produces the subtitle file, and holds the last human gate. A rejection must include a written reason; an analysis step turns that reason into targeted fixes, and the corrected video comes back for review.

## Position in the pipeline

- Pipeline: A (long-form). Stage 3 (Visual Generation) → **Stage 4** → Stage 5 (Publish).
- Trigger: automatic when status becomes `visuals_done`; status → `assembling`.
- Assembly is deterministic and local (FFmpeg); only the rejection analysis uses an AI call.

## Inputs

| Input | Source |
| --- | --- |
| Narration track + timing manifest | Stage 2 |
| Animated shot clips + shot list | Stage 3 |
| Approved script (exact caption text) | Stage 1 |
| Scene mood tags | Stage 1 scene breakdown |
| Music library (tracks, mood tags, license records) | Channel music library |
| Channel intro (3–5 s) and end card (about 10–20 s) | Channel brand assets |

## Music library

- Curated once by a human: 15–30 tracks from libraries whose license allows commercial use on YouTube, TikTok and Instagram without attribution (e.g. Pixabay Music, Mixkit).
- Each track is stored with: source URL, license type, the downloaded license file, mood tags, and attribution text if any.
- Import rejects any track with a non-commercial license (e.g. CC BY-NC). The pipeline never downloads music itself.
- Licenses are kept so any automated copyright claim can be disputed with proof.

## Assembly steps

1. **Music selection** — pick a track per scene by mood tag; avoid reusing the same track in back-to-back stories; crossfade at mood changes.
2. **Timeline** — intro → shots in manifest order → end card.
3. **Audio mix** — narration on top; music lowered automatically under speech (ducking); whole mix normalized to the YouTube loudness target.
4. **Subtitles** — an `.srt` file built from the exact script text and manifest timings (no speech recognition needed). Long lines are split for readability. Not burned into the picture.
5. **Render** — MP4, 1920×1080, H.264 video, AAC audio.

## Automated QA

| Check | Pass condition |
| --- | --- |
| Duration | Equals intro + narration + end card |
| Sync | Every shot starts at its manifest time, within one frame |
| Frames | No unintended black or frozen frames |
| Loudness | Within the target range for YouTube |
| Ducking | Music never louder than the voice during speech |
| Subtitles | Every script line present, timings match the manifest, no overlaps, line length within limits |
| Music licenses | Every track used has a license record allowing commercial, cross-platform use |
| File | Plays correctly; codec, resolution and frame rate as specified |

Assembly is deterministic, so a QA failure is retried once; a second failure means a bug and goes to `needs_attention`.

## Final review gate

- Status `awaiting_final_review`. The screen shows: the video player with subtitles toggleable, the shot list with thumbnails and timestamps, the music used with licenses, and the script.
- Actions:
  1. **Approve** — status `final_approved`; Stage 5 starts.
  2. **Reject with reason** — a written reason is **required** (e.g. "the owl looks different at 1:42 and the music is too loud in the chase scene").
  3. **Kill** — archive.

## Rejection analysis

Triggered by every final rejection; status `analyzing_rejection`.

- Claude reads the rejection text together with the script, shot list, timing manifest, music choices and subtitles, and produces a **correction plan**: a list of targets, each with a type, the affected IDs, and an instruction.

| Target type | What gets redone | Then |
| --- | --- | --- |
| `shot` | Only the named shots in Stage 3, with the instruction added to their prompts | Reassemble |
| `audio_line` | Only the named lines in Stage 2; if their length changes, clips for that scene are re-rendered from the existing images | Reassemble |
| `music` / `mix` | Music choice, volume or ducking in Stage 4 | Reassemble |
| `subtitles` | Subtitle file | Reassemble |
| `script` | Back to Gate B, with the rejection text as regeneration notes | Normal flow from Gate B |
| `story` | Back to Gate A, with the rejection text as notes | Normal flow from Gate A |

- After corrections, the pipeline runs on automatically and the video returns to final review, with the rejection text and the change list shown next to the player.
- Timestamps in the rejection text (e.g. "1:42") are mapped to shots and lines using the timeline.
- If the plan can't be determined with confidence, the story goes to `needs_attention`, where a reviewer picks the targets manually from the shot and line lists.

## Retry budget

- `final_rejections` counter, limit **3**. On the third rejection the story goes to `needs_attention` rather than the archive, since story, script, audio and visuals are already invested; a reviewer then decides to continue or kill.
- A video rejection counts only against `final_rejections`, even when it is routed back to the story or script. It never touches the Gate A/B counters, so a finished video can't be archived by a late rejection (decided 2026-10-04).

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Assembled, QA passes, reviewer approves | `final_approved` |
| 2 | Reject: a character looks wrong at a timestamp | Analysis maps it to that shot; shot regenerated; reassembled | `awaiting_final_review` |
| 3 | Reject: a word is mispronounced | Mapped to the audio line; line regenerated, scene clips re-timed; reassembled | `awaiting_final_review` |
| 4 | Reject: music too loud or wrong mood | Mix or music changed; reassembled | `awaiting_final_review` |
| 5 | Reject: "the ending doesn't land" | Mapped to script; back to Gate B with notes | `script_in_progress` |
| 6 | Reject: several issues at once | One plan with several targets; all fixed, then one reassembly | `awaiting_final_review` |
| 7 | Reject without a reason | Not allowed; the reject button stays disabled until text is entered | unchanged |
| 8 | Rejection too vague to map | Flag for manual target selection | `needs_attention` |
| 9 | Third final rejection | Flag; reviewer continues or kills | `needs_attention` |
| 10 | A used track has no valid license record | QA failure; track replaced from the library | `assembling` |
| 11 | Assembly QA fails twice | Treated as a bug; flag | `needs_attention` |
| 12 | App restarts mid-render | Render restarts from scratch (it's fast); approved inputs untouched | `assembling` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `visuals_done` | Automatic | `assembling` |
| `assembling` | QA passes | `awaiting_final_review` |
| `assembling` | QA fails twice | `needs_attention` |
| `awaiting_final_review` | Approve | `final_approved` → Stage 5 |
| `awaiting_final_review` | Reject with reason | `analyzing_rejection` |
| `analyzing_rejection` | Plan routes to shots, lines, music or subtitles | the affected stage, then back to `assembling` |
| `analyzing_rejection` | Plan routes to script or story | `script_in_progress` / `story_in_progress` |
| `analyzing_rejection` | Plan unclear, or third rejection | `needs_attention` |
| `awaiting_final_review` | Kill | `archived` |

## Logging

- Music chosen per scene, mix settings, loudness measured, every QA result.
- Every rejection: the text, the correction plan, what was redone, and the outcome.

## Decisions

- Curated local music library from free commercial libraries, license kept per track; non-commercial tracks rejected.
- Subtitles as a separate `.srt` file for long-form (burned-in captions belong to Pipeline B).
- Channel intro and end card on every video.
- Final rejection requires a written reason; an analysis step routes targeted fixes, and the video returns to final review.
- 3 final rejections, then `needs_attention` (not archive).

# Pipeline B · Clips From Any Video

A page on the management site where a person uploads a video file or pastes a YouTube link, and Pipeline B turns it into Shorts / TikToks / Reels. It reuses clip selection (B1), per-platform formatting (B3) and publish (B4) as they are. Two steps are new: an intake step that fetches and transcribes the video, and a reframe variant of B2 that crops real footage to vertical instead of re-generating images.

## Position in the pipeline

- **Intake (new)** → B1 Clip Selection → **B2 Vertical Re-assembly, reframe variant** → B3 Per-Platform Formatting → B4 Publish.
- Trigger: a person submits the form on the "Clips from a video" page. Nothing happens automatically.
- Independent of Pipelines A and C: no story, topic or character files are involved.

## The page

| Field | Notes |
| --- | --- |
| Video | File upload (mp4, mov, mkv, webm) **or** a YouTube link; exactly one |
| Source and permission | Required free text, e.g. "my own video" or "streamer X, clipping program, terms link". Stored with the job, like a music license record |
| Channel | Which of the owner's channels the clips are for (sets the closing line, branding and caption style) |
| Number of clips | 1–5; default 3 |
| Notes for the clip picker | Optional, e.g. "focus on the funny moments", "skip the intro" |
| Spoilers allowed | Default off: no clip contains the ending or main payoff, as in B1 |

The page also lists every external video job with its status, like the clip queue.

## Intake

1. **Fetch:** an upload is saved to the assets directory. A YouTube link is downloaded with **yt-dlp** (Unlicense) at the best quality up to 1080p. The yt-dlp version is pinned in config and logged with every download.
2. **Validate (ffprobe):** it must have a video and an audio stream, and its length must be within `storyforge.clips.max-source-minutes` (default 180). The same file (by content hash) is never stored twice; resubmitting it reuses the earlier intake.
3. **Transcribe:** Whisper (sidecar, GPU lock) with word-level timestamps, language auto-detected. Words are grouped into lines (sentences, or pauses of 0.7 s or more), which produces the same timing manifest shape B1 reads for stories.
4. **Scene cuts:** FFmpeg scene detection, saved so B2 can avoid cutting a clip mid-shot.

### Intake QA

| Check | Pass condition |
| --- | --- |
| Download | yt-dlp exits cleanly and the file passes ffprobe |
| Length | Within the configured maximum |
| Speech | At least 60 s of transcribed speech (B1 picks clips by what is said) |
| Manifest | Lines cover the transcript in order, with no overlaps |

A failed download is retried with backoff (an outage, never a strike), then flagged with yt-dlp's error message. The page offers "upload the file instead" as the fix.

## B1 — Clip selection (reused)

- Same rules and QA as B1, run on the intake manifest: 20–52 s slices on line boundaries, strong opening, self-contained, no overlaps, hook title, and no spoiler unless the job allows it.
- The person's notes are added to the prompt.
- Slices that start or end within 0.5 s of a scene cut snap to the cut.

## B2 — Reframe variant

Replaces steps 1–2 of B2 (vertical images, animation). Steps 3–7 stay, adapted as below.

1. **Reframe to 1080×1920**, mode chosen per job (default **blurred fill**):
   - **Blurred fill:** the full 16:9 frame centred, over a blurred, zoomed copy of itself. Nothing is cut off.
   - **Centre crop:** a 9:16 window from the middle of the frame. It fills the screen but may cut people off.
   - **Crop with offset:** a 9:16 window at a horizontal position the reviewer picks in B4.
   - Vertical sources pass through unchanged.
2. **Audio:** the original audio of the slice, normalized to the Shorts loudness target. No new narration. The closing line is optional per channel; it is off unless that channel has one recorded.
3. **Captions:** word-by-word from the Whisper word timings (1–3 words at a time, current word highlighted), inside the safe zone.
4. **Overlays:** hook title for the first ~3 s; a credit line ("Clip from …") when the source is not the owner's own video.

QA: B2's length, format, sync, captions, audio and frames rows apply; the image rows do not. This variant uses no GPU.

## B3 and B4 (reused)

- B3 call to action: "Full video on our channel" for the owner's own videos (YouTube Shorts link the source video as the related video); for permitted third-party sources, the credit and whatever the permission requires (e.g. a link to the creator).
- B4 unchanged, plus a "source and permission" line on each clip's publish package. Rejections route to B1, the B2 reframe variant or B3; 3 rejections kill a clip.

## Data model

| Table | Fields |
| --- | --- |
| `source_video` (new) | id, origin (`upload` / `youtube`), url, file key, content hash, title, duration, language, source and permission text, yt-dlp version, created_at |
| `clip_job` | Either `story_id` or `source_video_id` (exactly one), plus reframe mode, channel, notes, spoilers allowed |

## Status flow

The clip job gains two statuses before the existing ones:

`ingesting` → `transcribing` → `selecting` → `rendering` → `formatting` → `clip_ready` → `clip_approved` → `clip_published`
Side states: `needs_attention`, `clip_killed`.

Story-based clip jobs still start at `clips_queued`; external video jobs skip it because they don't wait for a story.

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Happy path, YouTube link to own video | Downloaded, transcribed, 3 clips picked, reframed, formatted | `clip_ready` |
| 2 | Happy path, uploaded file | Same as 1 without the download | `clip_ready` |
| 3 | Form sent without source and permission | Refused with a message | no job |
| 4 | Both a file and a link, or neither | Refused with a message | no job |
| 5 | Link is private, age-restricted or removed | Download fails; flagged with yt-dlp's message; page suggests uploading the file | `needs_attention` |
| 6 | yt-dlp broken by a YouTube change | Backoff, not a strike; then flag ("update yt-dlp or upload the file") | `needs_attention` |
| 7 | Video longer than the maximum | Refused at validation | `needs_attention` |
| 8 | Mostly music or no speech | Speech check fails; flag (manual slice picking still possible) | `needs_attention` |
| 9 | Same video submitted twice | Intake reused; a new clip job is created | `selecting` |
| 10 | Whisper needs the GPU while Pipeline A holds it | Waits for the GPU lock | `transcribing` |
| 11 | Centre crop cuts off the speaker | Reviewer rejects with "speaker cut off"; routed to B2 with crop offset or blurred fill | `rendering` |
| 12 | Non-English video | Transcribed and captioned in its own language; B3 text in that language | `clip_ready` |
| 13 | App restarts mid-download | Partial file discarded; download restarts | `ingesting` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| (form submitted) | Valid form | `ingesting` |
| `ingesting` | File fetched and valid | `transcribing` |
| `ingesting` | Download or validation fails | `needs_attention` |
| `transcribing` | Manifest written, QA passes | `selecting` (B1) |
| `transcribing` | Too little speech or sidecar failure | `needs_attention` |
| `needs_attention` | Retry, new file uploaded, or manual slices | `ingesting` / `transcribing` / `rendering` |
| `selecting` onwards | As in B1–B4 | — |

## Decisions

- Accepts an uploaded file or a YouTube link; links are fetched with yt-dlp. Downloading from YouTube is against YouTube's terms of service even for one's own videos. The owner accepted that for convenience; uploading the file is always available as the alternative.
- Every job records its source and permission. Only the owner's own videos or videos clipped with the creator's permission are submitted. Copyright claims and strikes land on the channel that posts the clips.
- Footage is reframed, never re-generated: blurred fill by default.
- Original audio only; no new narration. Captions come from the Whisper word timings.
- Clips are reviewed and posted by a human in B4, as for story clips.

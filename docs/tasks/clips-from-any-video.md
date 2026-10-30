# Clips from any video

Task record for `docs/pipeline-b/clips-from-any-video.md`.

## Done

- Clips page: upload or YouTube link, source and permission, channel, clip count, framing, notes, spoilers; job list.
- Intake with yt-dlp, ffprobe validation, content-hash reuse, Whisper word timings with language detection, scene cuts.
- B1 on the intake manifest, the B2 reframe variant, B3 and B4, per clip, with rejection routing.
- Tests: `ClipsScenariosTest` and `ClipSourceLimitTest` cover scenarios 1-13 with real FFmpeg and fake Claude, Whisper and yt-dlp.

## Not done

- Story clips: B0, and B1-B4 from a story's assets, including the regenerated vertical images. `clip_job.story_id` is ready for them.
- Per-channel caption styles and branding; a channel has only a name and an optional closing line for now.

## Setup

- `pip install -r sidecar/requirements.txt` installs the pinned yt-dlp; point `storyforge.clips.yt-dlp` at it if it isn't on PATH.
- Whisper's `small` model downloads on first use.

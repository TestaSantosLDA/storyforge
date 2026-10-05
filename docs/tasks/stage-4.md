# Tasks — Stage 4 (assembly and final review)

Spec: [stage-4](../pipeline-a/stage-4-assembly-final-review.md).

## Done

- [x] Music library page: import a track with its license file, source URL, moods; non-commercial licenses refused
- [x] Music per run of same-mood scenes, avoiding the previous story's tracks; unlicensed tracks never used
- [x] FFmpeg assembly: intro → shots → end card; narration on top, music ducked with a sidechain compressor, loudness normalised to −14 LUFS; H.264 + AAC, 1920×1080, 25 fps
- [x] Subtitles (`.srt`) from the exact script text and manifest timings; wrapped to 42 characters, two rows
- [x] Assembly QA: duration, sync, black frames, loudness, ducking, subtitles, music licenses, file; retried once, then needs attention
- [x] Final review page: player with subtitles, shots with timestamps, music with licenses, checks, script, last rejection
- [x] Approve / Reject with a required reason (button disabled until text) / Kill
- [x] Rejection analysis with Claude → correction plan; shots redrawn with the instruction (positive wording), lines re-recorded (with a pronunciation entry), mix or music changed, script or story sent back with notes
- [x] Only named shots and lines are redone; everything else is reused; the video comes back for review
- [x] Video rejections count only against `final_rejections`; the third stops for a person
- [x] Tests: Stage 4 scenarios with real FFmpeg assembly

## Open (human setup)

- [ ] Curate 15–30 music tracks with license files (Music page)
- [ ] Create the channel intro (3–5 s) and end card (10–20 s); set `storyforge.brand.intro` / `end-card`
- [ ] Manual target picking when a rejection can't be mapped (today: flagged with a request to reword)

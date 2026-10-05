# Tasks — Stage 3 (visuals)

Spec: [stage-3](../pipeline-a/stage-3-visual-generation.md).

## Done

- [x] 3A shot planning with Claude: lines grouped into shots at line boundaries, ≤4 characters, framing and camera
- [x] Code checks: coverage, scenes, cast, character count, durations from the timing manifest
- [x] Claude sees each line's on-screen seconds (pauses included); code splits long shots and merges short ones
- [x] 3B one image per shot from the plan, the style block and each character's reference sheet (front + side for 1–2 characters, front only for 3–4, 512 px)
- [x] Every character's action as its own sentence; the planner names each prop once (duplicated-prop finding)
- [x] Claude vision QA opens the shot and the references; 3 draws with new seeds, then needs attention with "accept the best images"
- [x] 3C `StorybookAnimator`: FFmpeg zoom/pan to the exact shot length at 1920×1080, 25 fps; clip QA (duration, format, black frames)
- [x] Plan re-timed from the current manifest on every run, so a corrected audio line re-renders only the clips that moved
- [x] Shots view on the story page; GPU lease held while drawing
- [x] Tests: every Stage 3 scenario (fakes) plus real FFmpeg renders of every camera move
- [x] FFmpeg installed on the dev PC (winget, 9.0.2)

## Open

- [ ] Per-story outfit or accessory notes for returning characters (shot prompts only)
- [ ] Edit a shot's description from the needs-attention screen (today: retry or accept the best image)
- [ ] Characters created before reference sheets existed (Tamsin, Oswin, Dot) have no sheets; they're drawn from their description only

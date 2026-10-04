# Pipeline B — Short-Form Vertical (Shorts / Reels / TikTok)

## Purpose

Turn each published long-form story into 2–3 vertical (9:16) clips for YouTube Shorts, TikTok and Instagram Reels. The clips are the discovery engine: every one points viewers back to the full episode. This page is the summary; each stage has its own detailed document.

## Trigger

- Starts automatically when a Pipeline A story is marked published. Reuses that story's assets: script, narration clips, timing manifest, shot descriptions, character references, music.

## Key design decisions

- **Re-generate, don't crop:** shots are re-generated as 9:16 illustrations with the same character references and style, so characters stay properly framed.
- **Excerpts, not summaries:** each clip is a strong self-contained moment that leaves the ending for the full episode.
- **No narrator intro;** an optional on-screen hook title instead. Every clip ends with one fixed narrator line: "Watch the rest of the episode on our channel!"
- **One human touchpoint:** review happens in the publish package; publishing is semi-manual.
- **GPU priority to Pipeline A;** clip jobs run when the GPU is free.

## Output

- 2–3 clips per story, each under 60 seconds, 1080×1920, burned-in captions, with three platform variants.

## B0 — Clip Queue

- One clip job per published story; waits for a free GPU. Detail: Pipeline B · Stage 0.

## B1 — Clip Selection

- Claude picks 2–3 slices: 20–52 s, on line boundaries, strong opening, self-contained, no ending spoiler, no overlaps; plus a hook title for each. Detail: Pipeline B · Stage 1.

## B2 — Vertical Re-assembly

- Vertical images re-generated and checked (including safe-zone framing), storybook animation, original narration and music, burned-in captions, closing line. Detail: Pipeline B · Stage 2.

## B3 — Per-Platform Formatting

- Same video file; platform-specific captions, hashtags, cover and call to action (Shorts link the full episode as the related video). Platform limits in config. Detail: Pipeline B · Stage 3.

## B4 — Publish

- Review and publish screen per clip; approve, reject with reason (routed to B1, B2 or B3), skip a platform, or kill. 3 rejections kill a clip. Shorts uploaded private first. Detail: Pipeline B · Stage 4.

## Key principles

- Reuses Pipeline A's assets; only the vertical images are new.
- Automated QA after every stage; failures stop and flag instead of flowing downstream.
- Every clip funnels viewers to the full YouTube episode.

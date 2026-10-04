# Pipeline B · Stage 4 — Publish

Stage B4 is where a human reviews the clips and posts them. Like Pipeline A, publishing is semi-manual: the page shows a publish package per clip and platform, the reviewer approves or rejects each clip, uploads it, and records the post's URL.

## Position in the pipeline

- B3 Per-Platform Formatting → **B4 Publish** → done.
- Trigger: clip status `clip_ready`.
- This is the only human touchpoint in Pipeline B; it replaces a separate clip-selection gate.

## Review and publish screen

For each clip:

- Video player, the slice it came from (lines and timestamps in the full episode), and the hook title.
- Per platform: copy buttons for caption and hashtags, cover frame, video download.
- Actions: **Approve**, **Reject with reason** (text required), **Skip a platform**, **Kill clip**.

## Publish checklist (per approved clip)

1. YouTube Shorts: upload as **private**, set the related video to the full episode, wait for the copyright check, then publish.
2. TikTok: upload with the TikTok caption and cover.
3. Instagram Reels: upload with the Instagram caption and cover.
4. Paste each post's URL into the page and mark that platform published.

A clip is complete when every platform is published or skipped. A clip job is complete when all its clips are complete or killed.

## Rejection handling

- A rejection requires a written reason. Claude maps it to a target, the same way as Pipeline A's rejection analysis:

| Target | Redone in |
| --- | --- |
| Wrong moment, spoiler, confusing out of context | B1 (reselect that clip) |
| A shot looks wrong, framing, captions | B2 (only the affected parts) |
| Caption text, hashtags, cover, call to action | B3 |

- Corrected clips return to this screen. Limit: **3 rejections per clip**, then the clip is killed (clips are cheap; other clips of the story are unaffected).

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Happy path | Clip approved, posted on all three platforms, URLs recorded | `clip_published` |
| 2 | YouTube copyright check flags the music | Dispute with the stored license, or reject the clip with "replace music" (redone in B2 with that track excluded) | `rendering` or unchanged |
| 3 | Reviewer rejects with a reason | Routed to B1, B2 or B3 | that stage |
| 4 | Third rejection of a clip | Clip killed; logged | `clip_killed` |
| 5 | Reviewer skips TikTok for one clip | That variant marked skipped | still counts as complete |
| 6 | Invalid URL pasted | Refused with a message | unchanged |
| 7 | Clip left unposted | No timeout; listed under "clips ready to upload" | `clip_ready` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `clip_ready` | Approve | `clip_approved` |
| `clip_approved` | All platforms published or skipped | `clip_published` |
| `clip_ready` | Reject, limit not reached | B1 / B2 / B3 |
| `clip_ready` | Third rejection, or Kill | `clip_killed` |

## Logging

- Approvals, rejections with reasons and routing, skipped platforms, post URLs, copyright claims and resolutions.

## Decisions

- Semi-manual posting on all three platforms; no multi-platform scheduling tool in the MVP.
- Review happens here, with the whole package visible; no separate selection gate.
- Rejections need a reason and are routed to the right stage; 3 rejections kill a clip.
- YouTube Shorts uploaded private first and published after a clean copyright check.

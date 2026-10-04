# Pipeline B · Stage 1 — Clip Selection

Stage B1 picks the 2–3 best self-contained moments from a published story to become vertical shorts. Each slice must hook in its first seconds, make sense on its own, stay under 60 seconds with the closing line, and leave the ending for the full episode.

## Position in the pipeline

- B0 Clip Queue → **B1 Clip Selection** → B2 Vertical Re-assembly.
- Trigger: clip job status `selecting`.
- No human gate here; the clips are reviewed in the B4 publish package.

## Inputs

- Approved script (speaker-tagged) and story outline, scene breakdown with moods, timing manifest.

## Selection step (Claude)

Claude proposes `target_clip_count` slices. Each slice:

- Starts and ends on **line boundaries** (never mid-sentence).
- **Starts strong**: a line of dialogue, a question, or a moment of action. No slow scene-setting openings.
- Is **self-contained**: a viewer with no context understands what's happening.
- **Doesn't spoil the ending**: the resolution and moral payoff stay in the full episode.
- Lasts **20–52 seconds**, leaving room for the fixed closing line so the total stays under 60 seconds.
- Doesn't overlap any other slice of the same story.
- Gets a short **hook title** for the optional on-screen text (e.g. "The fox's big mistake").

## Automated QA

| Check | Pass condition |
| --- | --- |
| Boundaries | Starts and ends on line boundaries in the manifest |
| Length | Slice 20–52 s; with closing line under 60 s |
| Overlap | No two slices share a line |
| Opening | First line is dialogue, a question or action (judged against the script) |
| Self-contained | An AI judge confirms the slice makes sense without context |
| No spoiler | The story's resolution and moral moment are not inside any slice |
| Hook title | Present, short, no trademarked names |

Failures regenerate automatically, up to 3 times, then `needs_attention`.

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Happy path | 3 valid slices selected | `rendering` (B2) |
| 2 | A slice runs over 52 s | Length check fails; reselect | retry |
| 3 | A slice includes the ending | Spoiler check fails; reselect | retry |
| 4 | Story too short to give 3 good slices | Fewer slices accepted (minimum 1); logged | `rendering` |
| 5 | Not even 1 valid slice after 3 attempts | Flag; reviewer can pick start and end lines manually | `needs_attention` |
| 6 | Clip rejected in B4 with "wrong moment" | That clip is reselected here with the rejection as notes; other clips untouched | `selecting` |
| 7 | LLM API failure | Backoff retries, not counted; then flag | `needs_attention` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `selecting` | QA passes | `rendering` (B2) |
| `selecting` | QA fails 3 times or API failure | `needs_attention` |
| `needs_attention` | Manual slice chosen, or resume | `rendering` / `selecting` |

## Decisions

- 2–3 slices per story, 20–52 s each, on line boundaries, strong opening, no ending spoiler.
- No narrator intro; an optional on-screen hook title instead.
- No separate human gate; clips are reviewed in the publish package.

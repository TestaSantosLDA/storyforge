# Pipeline B · Stage 0 — Clip Queue

Stage B0 starts Pipeline B automatically when a long-form story is published, creates a clip job for that story, and controls when clip work runs so it never competes with Pipeline A for the GPU.

## Position in the pipeline

- Trigger: a story reaches `published` in Pipeline A (Stage 5).
- Pipeline B: **B0 Clip Queue** → B1 Clip Selection → B2 Vertical Re-assembly → B3 Per-Platform Formatting → B4 Publish.
- No human action needed to start it.

## Data model

### Clip job (one per published story)

| Field | Notes |
| --- | --- |
| `id`, `story_id` | The source story |
| `status` | Clip-job status (see transitions) |
| `target_clip_count` | Default 3; at least 1 |
| `created_at` / `updated_at` | Audit |

### Clip (2–3 per job)

| Field | Notes |
| --- | --- |
| `id`, `job_id`, `index` | Order within the job |
| `start_line_id`, `end_line_id` | The script lines the clip covers |
| `shots` | Shots from Pipeline A that fall inside the slice |
| `status` | Clip status |
| `rejections` | Human rejection counter, limit 3 |
| `platform_urls` | One per platform once published |

## Inputs

- The story's full asset bundle from Pipeline A: approved script with speaker tags, scene breakdown with moods, timing manifest, per-line audio clips, shot list and images, music used, character files.

## Scheduling rules

- One clip job is created per published story, placed at the end of the clip queue.
- **GPU lock:** image generation from Pipeline A and Pipeline B never runs at the same time. Pipeline A has priority; clip jobs run when the GPU is free.
- Clip jobs process one at a time in the MVP (config value).

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Story published | Clip job created and queued | `clips_queued` |
| 2 | GPU busy with Pipeline A | Job waits; starts when the GPU is free | `clips_queued` |
| 3 | Story's asset bundle incomplete (a file missing) | Not started; flag listing the missing assets | `needs_attention` |
| 4 | Same story marked published twice | Only one clip job ever exists per story | unchanged |
| 5 | App restarts | Queue and statuses are in the database; jobs resume from their last status | unchanged |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| (story published) | Automatic | `clips_queued` |
| `clips_queued` | GPU free, top of clip queue | `selecting` (B1) |
| `clips_queued` | Assets missing | `needs_attention` |
| `needs_attention` | Resume | `clips_queued` |

## Decisions

- Pipeline B starts automatically on publish; no human trigger.
- 2–3 clips per story, from Pipeline A's assets.
- Pipeline A always has GPU priority; one clip job at a time.

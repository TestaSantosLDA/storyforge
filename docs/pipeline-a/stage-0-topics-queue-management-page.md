# Stage 0 — Topics, Story Queue & Management Page

Stage 0 is the local management page and the data behind it: topics (universes), the stories queued under them, and the character files that give each universe its recurring cast. It is the only way work enters the pipeline in the MVP.

## Position in the pipeline

- Pipeline: A (long-form). **Stage 0** → Stage 1 (Story & Script Generation).
- Served locally by the Spring app itself; no external hosting.
- Owns topics, stories, the queue, and the `queued` status. Run hands the top story to Stage 1.
- Nothing is generated at this stage. Topics are just descriptions; casts are generated per story in Stage 1.

## Data model

### Topic (a universe)

| Field | Type | Notes |
| --- | --- | --- |
| `id` | ID | Generated |
| `name` | Text | Required, e.g. "Fox & Owl in Whisperwood" |
| `description` | Text | Required; the universe, its setting and feel |
| `created_at` / `updated_at` | Timestamp | Audit |

### Story

| Field | Type | Notes |
| --- | --- | --- |
| `id` | ID | Generated |
| `topic_id` | Reference | Required; the universe it belongs to |
| `concept` | Text | Required; the idea Claude expands into a story |
| `moral` | Text | Required |
| `target_length_min` | Number | Minutes; default 4, allowed 3–5 |
| `notes` | Text | Optional guidance |
| `queue_position` | Number | Order in the single, channel-wide queue; meaningful only while `queued` |
| `status` | Enum | Single source of truth for pipeline position |
| `resume_status` | Enum | Set only in `needs_attention`: the working status Resume returns to |
| `attention_reason` | Text | Why the story was flagged |
| `topic_snapshot` | Text | Topic description captured at Run |
| Retry counters | Numbers | Four used by Stage 1 plus `final_rejections` (Stage 4), all start at 0 |
| `cost_usd` | Number | Sum of every attempt's cost; crossing the budget flags the story |
| `archive_reason` | Enum | Empty unless archived |
| `created_at` / `updated_at` | Timestamp | Audit |

### Character (one file per character, in the repo)

| Field | Notes |
| --- | --- |
| `id`, `name` | Unique across the channel |
| `topic_id` | The universe the character lives in |
| `kind` | `recurring` or `one_off` |
| `personality` | How they think, talk and behave |
| `visual_description` | Used by Stage 3 to draw them consistently |
| `reference_images` | Paths in the assets directory (keep-forever retention) |
| `voice` | Voice engine ID and settings; unique within the topic |
| `introduced_in_story` | The story whose approval created them |

Character files are created only when a story is approved at Stage 1 Gate A. On the management page they are visible per topic.

## Page views

- **Topics** — list, create, edit description; open a topic to see its stories and characters.
- **Queue** — all `queued` stories across all topics in order, with drag-to-reorder, Edit, Delete, and the Run button at the top.
- **In progress** — stories moving through the pipeline, with current status.
- **Awaiting approval** — stories at a human gate (story, script, or final review), each linking to its approval screen.
- **Needs attention** — stories stopped by a failure, with reason and Resume.
- **Published** — finished stories.
- **Archive** — archived stories with reason and Restore.

## Actions and rules

| Action | Allowed when | Effect |
| --- | --- | --- |
| Create topic | Always | Saved with name and description; nothing generated |
| Edit topic description | Always | Applies to stories started after the edit; in-flight stories keep the snapshot taken at Run |
| Delete topic | Topic has no stories that ever started and no characters | Removed with its queued stories |
| Add story | A topic exists | Validated, appended to the bottom of the queue as `queued` |
| Edit / delete story | Only while `queued` | Same validation as Add; delete closes the gap in the queue |
| Reorder | `queued` stories only | Drag to a new position; saved immediately |
| Run | At least one `queued` story and below the concurrency cap | Starts the **first story in the queue**: `queued` → `story_in_progress` |
| Restore | Story is `archived` | Bottom of the queue as `queued`; counters reset; history kept; editable again |

Once a story leaves `queued`, it is locked: no edit, delete or reorder.

## Validation

- Topic: `name` and `description` non-empty; topic names unique.
- Story: `topic_id`, `concept`, `moral` required; `target_length_min` within 3–5.
- Invalid input is rejected with field-level messages; nothing is saved.

## Scenarios

| # | Scenario | What happens | Result |
| --- | --- | --- | --- |
| 1 | Happy path: create topic, add story, drag to top, Run | Saved, reordered, started | Story `story_in_progress`; Stage 1 runs |
| 2 | Add story to a brand-new topic with no characters | Allowed; Stage 1 generates the cast | `queued` |
| 3 | Add with a missing required field | Rejected with a field message | Nothing saved |
| 4 | Edit or delete a started story | Hidden in the UI; backend refuses too | Unchanged |
| 5 | Edit a topic description while one of its stories is in flight | Saved; the in-flight story keeps its snapshot | Future stories use the new description |
| 6 | Delete a topic that has started stories or characters | Refused with a message | Unchanged |
| 7 | Run with an empty queue | Button disabled; backend returns "nothing to run" | Unchanged |
| 8 | Run clicked twice quickly | Top-story selection and status change happen in one transaction; no story starts twice | At most one extra start, if the cap allows |
| 9 | Reorder at the same moment as Run | Run takes whatever is first when the transaction commits | Exactly one story started |
| 10 | Run when `max_concurrent_stories` is reached | Button disabled with in-flight count; backend refuses | Unchanged |
| 11 | Restore an archived story | Bottom of the queue, counters reset | `queued` |
| 12 | App restarts | Everything is in the database and character files; stuck stories follow each stage's restart rule | Unchanged |
| 13 | Page open in two browser tabs | Each change saved immediately; other tab refreshes to stored state | Database state wins |

## Status transitions owned by Stage 0

| From | Event | To |
| --- | --- | --- |
| (new) | Add story | `queued` |
| `queued` | Run (first in queue only) | `story_in_progress` |
| `queued` | Delete | removed |
| `archived` | Restore | `queued` |

## Logging

- Every topic and story create, edit, delete, reorder, run and restore is logged with timestamp and before/after values.

## Decisions

- A topic is a universe with a description only; casts are generated per story in Stage 1.
- One channel-wide queue of stories; drag to reorder; Run starts the first story.
- Stories are editable and deletable only while `queued`; restored stories go to the bottom.
- Several stories can be in flight, capped by `max_concurrent_stories` (default 1 during development). Stories at a human gate or in `needs_attention` count as in flight (see `docs/foundations.md`).
- Character files live in the repo, one per character, recurring or one-off.
- No duplicate-story detection in the MVP.
- No login in the MVP: every page action is logged as `user:local`.
- Two open tabs: the queue page checks a small fingerprint of the stored queue every few seconds and reloads when it changes (scenario 13).
- Deleting a topic counts a restored story as started, because it left the queue once; the check uses the status history.
- A story can't move to another topic by editing; delete it and add it again under the other topic.

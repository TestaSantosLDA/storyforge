# Foundations

Foundations is build step 1: the Spring project, database schema, config, `AssetStorage`, character files, and the status service that every stage uses to move a story. It has no page and no AI calls; Stage 0 adds the page on top.

## Stack decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Database | PostgreSQL 17, run with `docker compose up -d` | Row locks and advisory locks make "pick next" safe. Docker is fine here; only the Python sidecar must run natively |
| Schema changes | Flyway migrations in `backend/src/main/resources/db/migration` | Versioned with the code |
| Build | Maven, Java 25, Spring Boot 4.1 | Already installed on the dev machine |
| Management page (Stage 0) | Thymeleaf, server-rendered | One app, no front-end build |
| Tests | JUnit + Testcontainers (real Postgres in Docker) | Locking behaviour can't be tested on an in-memory DB |
| Character files | YAML in `characters/`, no database table | Files are the source of truth; a table would store them twice (rule 4) |
| Prompt templates | Files in `prompts/` | Versioned in the repo |

## Status classification

Every story status has one kind. The kind drives the concurrency cap and the restart sweep, so no rule keys off a status name.

| Kind | Statuses | Counts as in flight | After a restart |
| --- | --- | --- | --- |
| Queued | `queued` | No | Left alone |
| Working | `story_in_progress`, `script_in_progress`, `audio_in_progress`, `visuals_in_progress`, `assembling`, `analyzing_rejection`, `preparing_publish` | Yes | The owning stage is started again; input-hash caching means only missing work is redone |
| Handoff | `story_approved`, `script_approved`, `audio_done`, `visuals_done`, `final_approved` | Yes | Advanced to the working status it hands to |
| Human | `awaiting_story_approval`, `awaiting_script_approval`, `awaiting_final_review`, `ready_to_upload`, `needs_attention` | Yes | Left alone |
| Terminal | `published`, `archived` | No | Left alone (`archived` can be restored) |

`needs_attention` counts as in flight: with `max_concurrent_stories = 1`, a flagged story must be resumed or killed before the next one runs.

## Status service rules

- `StoryStatusService` is the only code that writes `story.status`. Each change locks the row, checks the caller's expected current status, checks the move against the stage docs' transition tables (`StoryTransitions`), and logs from, to, who and why.
- **Resume target:** entering `needs_attention` stores the current working status in `resume_status`, so Resume needs no hidden state. Only a working status can be flagged. Leaving `needs_attention` always goes to a working status (Resume, or e.g. "send back to Gate B"), or to `archived` (Kill).
- **Run** takes a Postgres advisory lock, checks the in-flight count against the cap, then starts the first queued story and snapshots its topic description, all in one transaction. Adding, reordering and restoring take the same lock, so two Runs can never both see room under the cap.
- **Restore** puts the story at the bottom of the queue and resets all five counters; its history stays.

## Restart sweep

On startup: any leftover GPU lease is cleared, handoff statuses are advanced, and working statuses are handed to their stage again. Until a stage exists, the sweep only logs it.

## GPU lease

The GPU lock is a database lease (`gpu_lease` table), not an in-memory flag. One holder at a time; a holder renews by acquiring again; a crashed holder's lease expires after `storyforge.gpu.lease-ttl` (15 min). Pipeline A priority is up to the caller: Pipeline B asks only when no Pipeline A story is waiting for the GPU.

## Cost budget

Every attempt is logged with its cost, which is added to `story.cost_usd`. If the total passes `storyforge.pipeline.story-cost-budget-usd`, the story is flagged `needs_attention`. The default of $10 is a placeholder until a real budget is set.

## Still open (from the design review)

- Clip job vs clip statuses (Pipeline B) are not modelled yet; they come with B0.
- Whether a Gate A per-character "redo the look" counts as a story rejection.

## Scenarios

| # | Scenario | What happens | Test |
| --- | --- | --- | --- |
| 1 | Run with an empty queue | Nothing starts | `StoryStatusServiceTest` |
| 2 | Run starts the first queued story and snapshots the topic | `story_in_progress` | `StoryStatusServiceTest` |
| 3 | 20 simultaneous Runs with cap 2 | Exactly 2 stories start, the first two in queue order | `RunConcurrencyTest` |
| 4 | Run at the cap, with a story waiting at a gate | Refused | `StoryStatusServiceTest` |
| 5 | A move not in the stage docs, or from a stale status | Refused, nothing changes | `StoryStatusServiceTest` |
| 6 | Flag then Resume | Back to the stored working status | `StoryStatusServiceTest` |
| 7 | Restore | Bottom of queue, counters reset, history kept | `StoryStatusServiceTest` |
| 8 | App restarts | Handoffs advanced, working statuses redispatched, GPU lease cleared | `RestartSweepTest` |
| 9 | Two holders want the GPU; one crashed | One holder at a time; expired lease is taken over | `GpuLeaseTest` |
| 10 | Attempts push a story over its cost budget | Flagged `needs_attention` | `AttemptLogTest` |
| 11 | Asset keys try to leave the assets directory | Refused | `LocalAssetStorageTest` |
| 12 | Two characters with the same id or name | Second one refused | `CharacterStoreTest` |

# Tasks — Foundations (build step 1)

Record of what Foundations covers and where it stands. Spec: [docs/foundations.md](../foundations.md).

## Done

- [x] Spring Boot 4.1 / Java 25 / Maven project in `backend/`
- [x] Postgres for local dev (`docker-compose.yml`), Flyway migration `V1__foundations.sql`
- [x] Tables: `topic`, `story`, `status_change`, `attempt`, `gpu_lease`
- [x] Config for every CLAUDE.md default (`StoryforgeProperties`, `application.yml`), paths from env vars
- [x] `AssetStorage` interface + `LocalAssetStorage` (atomic writes, path-escape protection)
- [x] `InputHash` cache keys for generated assets (rule 4)
- [x] Character files as YAML in `characters/` (`CharacterStore`, unique id and name)
- [x] Status model: every status classified (queued, working, handoff, human, terminal)
- [x] Allowed transitions collected from all Pipeline A stage docs (`StoryTransitions`)
- [x] `StoryStatusService`: Run under an advisory lock, transition, flag with resume target, resume, archive, restore
- [x] Status change log with trigger and reason; attempt log with cost
- [x] Per-story cost budget that flags `needs_attention`
- [x] Database GPU lease with expiry
- [x] Restart sweep: advance handoffs, redispatch working stories, clear GPU lease
- [x] 74 tests against real Postgres (Testcontainers); concurrency test checked to fail without the lock
- [x] Docs: `docs/foundations.md`, Stage 0 data model, CLAUDE.md, README

## Open

- [ ] Push and open the PR once PR #1 (spike findings) is merged
- [ ] Set a real per-story cost budget (placeholder: $10)
- [ ] Decide: do rejections routed back from final review count against Gate A/B budgets (and can they archive a finished video)?
- [ ] Decide: does a Gate A "redo this character's look" count as a story rejection?
- [ ] Pipeline B clip job / clip statuses (comes with B0)

## Next step

Stage 0: management page (topics, stories, queue with drag-to-reorder, Run, archive/restore, views per status), built on `StoryStatusService`.

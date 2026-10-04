# Tasks — Stage 0 (management page)

Record of what Stage 0 covers and where it stands. Spec: [stage-0](../pipeline-a/stage-0-topics-queue-management-page.md).

## Done

- [x] Topic create (unique name), edit description, delete (refused with started stories or characters)
- [x] Story add, edit and delete while `queued`; backend refuses once started
- [x] Field-level validation messages; nothing saved on invalid input
- [x] Queue page with drag-to-reorder (saved immediately) and Run with in-flight count and cap
- [x] Views: In progress, Awaiting approval, Needs attention (with Resume), Published, Archive (with Restore)
- [x] Story page with facts, actions and full status history
- [x] Before/after change log for every topic and story action (`entity_change` table)
- [x] Second open tab reloads when the stored queue changes
- [x] Tests: one per Stage 0 scenario plus page tests (94 total passing)
- [x] Tried in the browser against local Postgres: create, drag-to-reorder, Run, story page
- [x] Fix: relative paths in config (e.g. `../characters`) failed to bind; found by running the real app

## Open

- [ ] Push once PR #1 is merged (stacked on `foundations`)
- [ ] Approval screens (Gate A, Gate B, final review) come with their stages; for now "Awaiting approval" links to the story page

## Next step

Stage 1: story + cast (Gate A, with character reference sheets) and script (Gate B). Needs the Claude API key and the sidecar's image endpoint.

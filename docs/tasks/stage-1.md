# Tasks — Stage 1 (story, cast and script)

Record of what Stage 1 covers and where it stands. Spec: [stage-1](../pipeline-a/stage-1-story-and-script.md).

## Done

- [x] `LlmEngine` interface; `ClaudeCodeEngine` runs `claude -p` headlessly on the owner's plan (no API key)
- [x] Outage backoff (never a strike); usage limit flags with the reset time and resumes automatically (`auto_resume_at`)
- [x] Versioned prompt templates and JSON schemas in `prompts/`
- [x] Step 1A: story + cast generation; code checks (cast ids, name and voice clashes) + Claude-judged QA
- [x] Step 1B: script + scene breakdown; code checks (length, cast lock, speaker tags, scene mapping) + Claude-judged QA
- [x] Retry counters and archive on limit; reviewer notes and failed checks fed into the next attempt
- [x] Gate A and Gate B pages: Approve, Regenerate with notes, Kill; earlier versions listed
- [x] Character files created on Gate A approval (voice assigned; rolled back if a name was taken meanwhile)
- [x] Drafts stored as numbered versions (`story_draft`), never overwritten
- [x] Status-driven dispatcher: entering a working status starts its step after commit
- [x] Tests: every Stage 1 scenario with a scripted fake Claude
- [x] Real run on the dev machine through Claude Code

## Open

- [ ] Character reference sheets: need the image sidecar (build step 4); Gate A shows a placeholder until then
- [ ] Pick the narrator voice (placeholder `bm_george`) from the Kokoro samples
- [ ] Confirm the Claude plan's terms allow this automated use for the channel
- [ ] Push once PR #1 is merged (stacked on `stage-0`)

## Next step

Python sidecar (build step 4): image endpoint first (reuse the Flux 2 Klein spike), then TTS and transcription.

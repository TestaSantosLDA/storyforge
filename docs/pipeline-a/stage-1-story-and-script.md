# Stage 1 — Story & Script Generation


Stage 1 turns a story concept into an approved story, an approved cast, and an approved, speaker-tagged script. It has two human gates — story (with cast) and script — because both are cheap to fix on paper and expensive to fix after audio and visuals.

## Position in the pipeline

- Pipeline: A (long-form). Stage 0 (Topics & Story Queue) → **Stage 1** → Stage 2 (Narration / Audio).
- Trigger: Run on the management page starts the first story in the queue; status `queued` → `story_in_progress`.
- Two steps in order: **1A Story + cast** → Gate A → **1B Script** → Gate B. Once Gate B approves, Stages 2–4 run automatically.

## Inputs

| Input | Source |
| --- | --- |
| Story concept, moral, target length, notes | Story row (Stage 0) |
| Topic description (the universe) | Snapshot taken at Run, so later topic edits don't change an in-flight story |
| The topic's existing characters | Character files |
| Full channel character roster | All character files, for the distinctness check |
| Reviewer correction notes | Previous rejection, only on regeneration |

## Audience

- Target: **families watching together** — older kids and adults as the core audience, with content that is fully safe for younger children in the room.
- Kid-safe always: no frightening, violent, or inappropriate content.
- Not toddler-directed: stories, humour and themes must also work for adults (layered jokes, real stakes, wit), not only for very young children.
- This matches the channel's "not made for kids" setting; both the story prompt and QA enforce it.

## Step 1A — Story + cast generation

- Claude expands the concept into a story outline: setup, problem, journey, resolution, and the moment the moral lands.
- Alongside it, Claude produces the cast for this story:
  - **Existing characters** from the topic are reused by reference and must behave consistently with their character files.
  - **New characters** are proposed with name, personality, visual description (its identifying features must be large shapes and colours, such as fur colour, clothing, or spectacles; details smaller than an ear are not reliably drawn), proposed voice, recurring or one-off flag, and the reason they exist in this plot.
- The fixed channel narrator is always present and is not part of the cast.

### Automated QA — story

| Check | Pass condition |
| --- | --- |
| Structure | All story beats present |
| Moral | Stated moral clearly delivered |
| Cast justification | Every new character has a plot reason |
| Visual distinctness | No new character's name or visual description is too similar to any character in the full channel roster |
| Voice distinctness | Proposed voices are unique within the topic (may repeat across topics) |
| Consistency | Existing characters act in line with their character files |
| Tone and safety | Family-appropriate; no frightening, violent or off-brand content |

### Gate A — story and cast approval

- Status `awaiting_story_approval`. The screen shows the story, the reused characters, and each proposed new character with its reason.
- Actions: **Approve**, **Regenerate with notes**, **Kill**.
- On Approve: a character file is created for every new character (recurring or one-off), voices are assigned, and the cast is **locked** for this story. Status `story_approved`; Step 1B starts automatically.

### Character reference images (generated in Step 1A)

- For every **new** character, a reference sheet is generated in parallel with the story: front view, side view, and a few expressions, all in the channel style. Existing characters reuse their stored images.
- Sheets are made by the local sidecar (Flux 2 Klein 4B) after the story text passes QA, since the new cast is only known then. Claude checks them by opening the three image files. If the sidecar is down, the story is flagged without a strike; Resume regenerates only the missing sheets, not the story. With `storyforge.images.engine=none` sheets are skipped and Gate A says so.
- On approval the sheet is copied to `characters/<id>/` in the assets directory (keep-forever) and listed in the character file's `reference_images`.
- The front view is a **three-quarter** front view: straight-on, tailed animals got a tail on each side.
- The **front view is generated first** and the side view and expressions are generated from it as a reference, so the whole sheet shows one design.
- Automated QA: one character per image, channel style followed, matches the character's visual description, **clean anatomy (correct count of tails, ears, limbs, eyes)**, visually distinct from every character in the full roster, no resemblance to known trademarked characters. Anatomy matters here more than anywhere else: Stage 3 copies the reference faithfully, flaws included (the 2026-10-04 spike's two-tailed fox reappeared in later scenes).
- A failing sheet is redrawn automatically with a new seed and the same prompt (failures are never named in the image prompt; see Stage 3 retry rules), up to 3 draws per character (`storyforge.images.sheet-attempts`). If none passes, the best draw goes to Gate A with its failed checks shown, and the reviewer approves it anyway or regenerates. **Changed 2026-10-05:** sheet draws no longer count toward `story_qa_retries`; in the first real runs, three new characters used up all five retries and archived a good story.
- Sheet QA allows for the channel style's rounded bodies; it fails a design only for a wrong or missing colour, clothing item or accessory, or for the three images disagreeing.
- Gate A shows each new character's reference sheet next to their description and reason. Regenerate-with-notes can target one character's look (e.g. "make the frog smaller and greener") without redoing the whole story.
- On Approve, the images are saved under the character's file (keep-forever retention). Stage 3 uses them as references for every image of that character.

## Step 1B — Script generation

- Input: approved story + locked cast + fixed narrator.
- Output:
  - Script with **every line tagged with its speaker** (narrator or a character ID).
  - Scene breakdown: ordered scenes, each with a visual description and the lines it covers.
  - Structure kept language-neutral so translation slots in later.
- Every attempt is versioned and stored, never overwritten.

* Each scene in the breakdown also carries a **mood tag** (calm, playful, tense-but-gentle, happy ending). Stage 4 uses it to pick background music.

### Automated QA — script

| Check | Pass condition |
| --- | --- |
| Length | Word count fits the target duration (roughly 400–650 words for 3–5 min) |
| Story fidelity | All approved story beats present, in order |
| Moral | Moral clearly delivered |
| Cast lock | Every speaker is the narrator or an approved cast member; any other character is a failure |
| Speaker tags | Every line has exactly one speaker |
| Scene mapping | Every line belongs to a scene; the breakdown parses |
| Reading level, tone, safety | Appropriate for the family audience |
| Format | Valid structured output |

### Gate B — script approval

- Status `awaiting_script_approval`. The screen shows the script with speakers, the scene breakdown, the approved story for comparison, and previous versions.
- Actions: **Approve** (status `script_approved`; Stages 2–4 run automatically), **Regenerate with notes**, **Kill**.

## Retry budgets

Each gate has its own two counters; either limit reached archives the story.

| Counter | Increments when | Limit |
| --- | --- | --- |
| `story_qa_retries` | Story QA fails, auto-regenerate | 5 |
| `story_rejections` | Reviewer regenerates at Gate A | 3 |
| `script_qa_retries` | Script QA fails, auto-regenerate | 5 |
| `script_rejections` | Reviewer regenerates at Gate B | 3 |

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Story + cast pass QA, approved; script passes QA, approved | `script_approved` |
| 2 | Story QA fails, budget left | Increment, regenerate with failed check as feedback | `story_in_progress` |
| 3 | Proposed character too similar to an existing one | Story QA failure; regenerate with the clash named | `story_in_progress` |
| 4 | Reviewer regenerates story with notes, budget left | Increment, regenerate with notes | `story_in_progress` |
| 5 | Any story counter reaches its limit | Archive with reason | `archived` |
| 6 | Reviewer kills at Gate A | Archive; no character files created | `archived` |
| 7 | Script introduces an unapproved character | Script QA failure (cast lock); regenerate | `script_in_progress` |
| 8 | Script QA fails or reviewer regenerates, budget left | Increment, regenerate | `script_in_progress` |
| 9 | Any script counter reaches its limit | Archive with reason; characters approved at Gate A stay in the roster | `archived` |
| 10 | Reviewer kills at Gate B | Archive; approved characters stay in the roster | `archived` |
| 11 | LLM API error or timeout | Backoff retries, not counted as strikes; then flag | `needs_attention` |
| 11b | Claude usage limit reached | No backoff; flag with the reset time (or retry in 1 h if none is given) and resume automatically when it passes; never a strike | `needs_attention` → `story_in_progress` / `script_in_progress` |
| 11c | Answer doesn't match the required structure | Counts as a failed Format check | `story_in_progress` / `script_in_progress` |
| 12 | Waiting at either gate for a long time | No timeout; visible in the page's "awaiting approval" list | unchanged |
| 13 | App restarts mid-generation | Rows stuck in an `_in_progress` status are re-run; attempt not double-counted | unchanged |
| 14 | Archived story restored | Back to the bottom of the queue; all four counters reset; restarts at Step 1A; history kept | `queued` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `queued` | Run (top of queue) | `story_in_progress` |
| `story_in_progress` | QA pass | `awaiting_story_approval` |
| `story_in_progress` | QA fail, budget left | `story_in_progress` |
| `awaiting_story_approval` | Approve | `story_approved` → `script_in_progress` |
| `awaiting_story_approval` | Regenerate, budget left | `story_in_progress` |
| `script_in_progress` | QA pass | `awaiting_script_approval` |
| `script_in_progress` | QA fail, budget left | `script_in_progress` |
| `awaiting_script_approval` | Approve | `script_approved` |
| `awaiting_script_approval` | Regenerate, budget left | `script_in_progress` |
| any step | Budget exhausted, or Kill | `archived` |
| any `_in_progress` | API failure after retries | `needs_attention` |
| `needs_attention` | Resume | the step that failed |
| `archived` | Restore | `queued` |

## How it runs (MVP)

- Claude is reached through the local Claude Code CLI (`claude -p`) with no tools, no saved session, the channel system prompt, and a JSON schema the answer must match. Prompt templates and schemas are versioned files in `prompts/`.
- QA is two layers: code checks (cast ids, name and voice clashes, word count, cast lock, speaker tags, scene mapping) and a separate Claude call that judges the rest (structure, moral, fidelity, tone).
- A counter archives the story when it reaches its limit (the 5th QA failure, the 3rd rejection).
- Every draft that passes QA is stored as a numbered version (`story_draft`); rejected versions keep the reviewer's notes, which go into the next attempt.

## Logging

- Every attempt logs input, prompt version, model, raw output, each QA result, counter values, cost, and timestamps.
- Every status change logs who or what triggered it and why.
- Character file creation logs the story that introduced the character.

## Decisions

- Two human gates: story + cast (A) and script (B). Automatic script approval may be revisited later.
- Cast is generated with the story, approved at Gate A, then locked; characters enter the universe only through Gate A.
- Every new character, recurring or one-off, gets a character file, so future characters are checked against it.
- Voices unique within a topic; visual distinctness checked channel-wide.
- One fixed narrator for the channel; one visual style for the channel (defined in Stage 3).
- Budgets per gate: 5 QA retries, 3 human rejections. Restore resets all counters.
- API outages never count as strikes. Any reviewer can act at either gate.
- Characters approved at Gate A stay in the roster even if the story is later archived.

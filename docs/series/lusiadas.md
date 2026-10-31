# Series — Os Lusíadas: Remixed

> **Status: approved spec (owner, 2026-10-30).** Every place this series conflicted with a documented decision is listed under [Resolved conflicts](#resolved-conflicts) as **C1–C12**. The owner accepted all twelve proposed defaults, and the affected docs (CLAUDE.md, the overview, Stage 1, Stage 4, Pipeline B and Pipeline D) were updated to match.

## Purpose

An animated motion-graphics retelling of *Os Lusíadas* (Luís de Camões, 1572) and the Portuguese history inside it, in English, for an international teen audience (13–19). It should feel like a modern YouTube explainer, not a school lesson: teens find it cool, and a teacher can still put it on in class.

One season of twelve episodes (Ep.00 already exists), 8–11 minutes each in 16:9 (at least 8 minutes so YouTube allows mid-roll ads; owner, 2026-10-30), plus 2–3 vertical Shorts cut from each episode. The series runs on Pipeline D (Motion Pictures): Claude writes every scene as code against the scene kit, the app renders it deterministically and assembles it. No image model and no video model are needed: every character, map and prop is drawn by the kit.

## Inputs this spec is built on

- The owner's brief (2026-10-30), quoted in the profile, beat sheet and rules below.
- **Ep.00 — "The Poet Who Swam"** (5:00, 16:9, 1080p30), the finished reference episode. Its source (`ep0.html`, `render.py`, `music.py`) is meant to live in `reference/lusiadas-ep00/`. `ep0.html` is a deterministic timeline engine: `render(t)` draws the exact frame for time `t`, Playwright screenshots every frame and FFmpeg encodes. That is the Pipeline D time contract (`seek(t)`) in landscape, so it seeds the landscape side of `motion-kit/`. `ep0.html` is in the repo; `render.py` and `music.py` were not supplied, and `motion-kit/render/render.js` replaces the renderer.
- **Motion test 2, "Os Lusíadas Remixed Ep.1"** (40 s, 9:16, 2026-10-30): flat geometric gods drawn in code, gods' group chat, typed verse in Portuguese with an English line under it (Canto I, st. 30), stat card, LEGEND stamp, original synthesised score. Its source (`lus.js`, `render.js`, `narrate.py`, `sound.py`) is in the project files under `motion-test/lusiadas/` and seeds the vertical side of the kit components below.

## How the series runs on Pipeline D

| Pipeline D step | What changes for this series |
| --- | --- |
| Topic | One topic, "Os Lusíadas — Remixed", with profile `epic`, marked as a **series** (C5), on its **own channel** (C2) |
| Stories | One story per episode, with an episode number, a source excerpt (Canto + stanza range) and an optional recap (C5) |
| D1 — Script + cast | ~9 min script following the beat sheet below, grounded in the episode's source excerpt; claims list tags each claim as documented history, tradition or legend; recap and next-episode teaser written from the neighbouring episodes (C5); one gate (C6) |
| D2 — Narration | Stage 2 as written, with the long-form pauses (0.3 / 0.6 / 1.2 s), not D's short-form ones; Portuguese names added to the pronunciation dictionary |
| D3 — Motion scenes | Landscape 1920×1080 (C1); all characters drawn by the kit, no Flux (`illustrations: none`); Camões and other long-dead historical figures as stylised code characters (C3, C10); series kit components below |
| D4 — Assembly | Landscape timeline, title sting instead of a channel intro, teaser as the last 10 s (C7); Stage 4 subtitles instead of word captions; music and sound hits from the library (C11) |
| D5 — Publish | Stage 5 long-form metadata: chapters from the chapter tags, sources from the claims list, translation credits, code-drawn thumbnails; then 2–3 vertical Shorts per episode (C8) |

## The `epic` video profile

A fourth starter profile, `channel/video-profiles/epic.yaml`, built as the first step after this spec is approved.

| Field | Value |
| --- | --- |
| `genre` | `historic` |
| `facts` | `checked`, with every claim tagged `documented`, `tradition` or `legend` |
| `voices` | `narrator_only` (later: `cast`, for the gods' dialogue) |
| `narrator_voice` | the series channel's narrator (picked from the Kokoro samples at setup, C2) |
| `frame` | `landscape` (new field, C1) |
| `target_seconds` | 540, allowed 480–660 (8–11 min). The 8-minute floor is a hard limit: below it YouTube offers no mid-roll ads (owner, 2026-10-30). Ep.00 runs 8:30 |
| `beat_seconds` | 3–5 |
| `motion` | `snappy` |
| `on_screen_text` | heavy |
| `illustrations` | `none` (new field: the kit draws everything, no Flux cut-outs) |
| `opening` | `title_sting` (new field, C7) |
| `tone` | Witty, confident, a little irreverent, never mocking the source. Teens should find it cool, and teachers should still be able to use it. |
| `structure` | The episode beat sheet below |
| `visual_style` | Flat geometric motion graphics. Ink navy `#0a0f24` base, azulejo blue `#1f5fbf`, gold `#ffc83d` (heroes, key words), coral `#ff4d3d` (danger, impacts), cyan `#3de1ff` (labels, translations), cream `#f6efe2` (text). Heavy uppercase sans headlines, italic serif for poetry, monospace for tags and dates. Azulejo tile patterns and Manueline / armillary-sphere motifs as the Portuguese signature. Subtle grain and vignette. White flash plus a sound hit on big reveals. |
| `music` | on; `music_mood`: "epic", "tense", "playful", "wonder" per chapter |
| `audience` | not made for kids (teen and family audience) |

The fonts used in the motion test (Inter, Playfair Display, JetBrains Mono, all SIL Open Font License, licence files bundled) fit the type spec and are the default kit fonts for this profile.

## Episode beat sheet (`structure`)

| # | Beat | Rule |
| --- | --- | --- |
| 1 | **Cold open** | The episode's most dramatic moment, ≤ 15 s, no intro, no channel branding before it |
| 2 | **Title sting** | Series logo + episode number and title, about 3 s, code-rendered per episode (C7) |
| 3 | **Recap** *(optional)* | "Previously on…", ≤ 15 s, only when the story's recap is on (C5) |
| 4 | **Chapters** | 4–6 chapters, each opened by a chapter tag pill; each chapter becomes a YouTube chapter |
| 5 | **Verse Moment** | Exactly one: original Portuguese typed on screen, our English translation under it, with Canto and stanza shown |
| 6 | **Fact or Legend?** | Exactly one: a claim from the episode put to the viewer, then answered with the LEGEND stamp or a "DOCUMENTED" stamp, and the source named |
| 7 | **Next episode teaser** | The last 10 s, built from the next episode (C5); the last episode of the season teases the season as a whole |

Beats 5 and 6 sit inside a chapter, wherever the story fits them. D1's QA checks every beat is present, in order, within its time limit.

## Season 1

| Ep. | Title | Source | Notes |
| --- | --- | --- | --- |
| 00 | The Poet Who Swam | Camões's life | Done; the reference episode |
| 01 | The Gods Have a Group Chat | Canto I (Olympus council, Venus vs Bacchus) | First real run |
| 02 | Trap in Mozambique | Cantos I–II | |
| 03 | Origin Story | Canto III (Lusus, Viriato, Afonso Henriques, Ourique) | Heavy on legend: Ourique's vision is tradition, not documented history |
| 04 | The Queen Crowned After Death | Canto III (Inês de Castro) | The posthumous coronation is legend; family-safe handling of her death |
| 05 | 1385: Underdogs | Canto IV (succession crisis, Aljubarrota) | |
| 06 | The Old Man of Restelo | Canto IV | |
| 07 | Adamastor | Canto V | Flagship episode |
| 08 | The Twelve of England & the Storm | Canto VI | |
| 09 | Arrival in Calicut | Cantos VII–VIII | Represent the people of Calicut respectfully, not as props |
| 10 | The Island of Love | Canto IX | Romantic and mythological only; nothing explicit, no nudity, kept within family-safe QA |
| 11 | The Machine of the World | Canto X | Season finale; teaser previews season 2 or the season as a whole |

Each episode is a story under the series topic, created on the management page with its episode number, title (as the concept's headline), concept and exact stanza range. Precise stanza ranges are filled in when each story is created; D1 refuses to run without one.

## Characters

All characters are drawn in code by the kit, in the flat geometric style, never generated as images.

- **Camões, the host.** Recurring. One eye (the right eye closed / patched), laurel wreath, flat geometric shapes in the profile palette. He pops in to comment, react and introduce the Verse Moment. He is a historical person, so he needs the exception in **C3**; how his lines may be written is **C4**; how his character file works without Flux reference images is **C10**.
- **The gods** (Jupiter, Venus, Bacchus, Mars, Neptune and others), **Adamastor**, the **Old Man of Restelo**, the **nymphs**: mythological or fictional characters from a public-domain poem. They are not real people, so no exception is needed for them, but naming them must not trip the "no existing character" rule (**C9**).
- **Vasco da Gama, Inês de Castro, Pedro I, Afonso Henriques, Nuno Álvares Pereira, João I, Viriato** and other historical figures: long-dead real people, drawn stylised under the same exception as Camões (**C3**).
- Every character gets a character file (YAML, in `characters/`), recurring or one-off, as today.

## Scene kit additions

Recurring components for this series, built into `motion-kit/` with a fixed API so Claude reuses them instead of redrawing them per scene. Each works in both frames (C1, C8).

| Component | What it does |
| --- | --- |
| Chapter tag pill | Monospace pill ("CHAPTER 2 · THE STORM") that slides in and opens a chapter |
| Character stat card | Name, years, role and 2–3 stats with bars, for introducing a figure |
| Season route map | Map of the 1497–99 voyage that grows each episode: the stops reached up to the current episode are drawn, the new leg animates in. Stops and order live in a versioned series file (`series/lusiadas/route.yaml`), so the map is deterministic per episode |
| Gods' chat | Group-chat bubbles with avatars, typing dots and timed messages, for divine dialogue |
| Count-up | Numeric count-up tied to a word in the narration (dates, distances, crew numbers) |
| "LEGEND" stamp | Stamp slam with a sound hit, plus a matching "DOCUMENTED" variant for Fact or Legend? |
| Typed verse | Portuguese verse typed line by line in italic serif, our English translation fading in under it in cyan, with "Canto X · st. N" |
| Camões pop-in | The host rig entering from a frame edge, with a few poses (point, shrug, facepalm, wink) and a speech bubble |
| Azulejo / Manueline backdrops | Tile patterns, armillary sphere and rope motifs as parametric backgrounds |
| Flash hit | White flash on a frame, paired with a sound hit from the library (C11) |

## Accuracy and rights

On top of Pipeline D's rules for `facts: checked`:

- **Documented vs tradition vs legend.** Every claim in the claims list carries a `kind`: `documented`, `tradition` or `legend`. The script and the screen keep them apart: legend is always framed as legend ("the story goes…", the LEGEND stamp). QA fails a script that states a tradition or legend as documented fact. Gate A shows the kind next to each claim.
- **The poem.** *Os Lusíadas* (1572) is in the public domain and can be quoted. The Portuguese text comes from a public-domain edition stored in the repo with its provenance (`sources/lusiadas/`, one file per Canto, stanza-numbered). Verse Moments quote it verbatim.
- **Translations.** Modern English translations are usually under copyright and are never used. Translations on screen are either **our own**, written by Claude in D1 and shown at Gate A next to the original, or from a pre-1929 public-domain translation: **Mickle (1776), Aubertin (1878) or Burton (1880)**. Whichever is used is recorded per verse and credited on screen ("trans. Burton, 1880" / "our translation") and in the description.
- **Grounding.** The script must be grounded in the episode's source excerpt: D1 receives the stanzas verbatim, and every chapter maps to stanzas in the excerpt or to claims in the claims list (C5). Events outside the excerpt are allowed only as claims with sources.
- **Sources in the description.** The sources from the claims list go in the YouTube description, grouped as documented / tradition / legend, with the poem edition and translation credits.
- **Respect.** Witty about the story, never mocking the source, the poet, Portugal or the peoples the voyage met. Colonial violence is acknowledged where the episode touches it, not glorified.
- **Real people** (C3), **the host's lines** (C4) and **public-domain characters** (C9) follow the resolved conflicts below.

## Shorts from each episode

2–3 vertical Shorts per episode, cut from the finished episode. They are made as decided in **C8**: B1 picks the slices and B2 re-renders their scenes at 9:16 from the same scene code. They reuse the episode's narration (no new speech), end on the series channel's closing line ("Watch the full episode on our channel!", recorded once in the series narrator's voice), carry word captions, and publish to the series channel's Shorts, TikTok and Reels (C2).

## Data changes

- `channel` table and `topic.channel_id` (C2).
- Profile fields `frame` and `illustrations`; `target_seconds` becomes a target plus an allowed range (C1).
- `topic.is_series`; `story.season`, `story.episode_number` (unique per topic and season), `story.source_excerpt` (JSON list of `{canto, from_stanza, to_stanza}`), `story.recap_enabled` (C5).
- `claims[].kind`: `documented` | `tradition` | `legend`; `verses[]` with `canto`, `stanza`, `pt`, `en`, `translation_source`.
- Character file: `drawn_by: code`, `kit_component`, `historical: true`, `died` (C3, C10).
- Repo files: `channel/video-profiles/epic.yaml`, `sources/lusiadas/canto-01.txt` … `canto-10.txt` with a provenance note, `series/lusiadas/route.yaml`, `reference/lusiadas-ep00/`.

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path, Ep.01 | Script follows the beat sheet, claims tagged, verse quoted verbatim with our translation; approved; narrated; ~40 landscape scenes rendered; assembled with title sting and teaser; reviewed; long-form metadata with chapters and sources | `ready_to_upload` |
| 2 | Story created without a source excerpt | Saving the story is refused with the reason | unchanged |
| 3 | Script states Ourique's vision as fact | QA fails (legend presented as documented), retried with the reason | `story_in_progress` |
| 4 | Verse Moment text differs from the stored stanza | QA fails (quote not verbatim), retried | `story_in_progress` |
| 5 | Script uses a modern copyrighted translation | QA fails (translation source not ours or pre-1929), retried | `story_in_progress` |
| 6 | Script misses the Fact or Legend? beat, or the cold open runs 25 s | QA fails (beat sheet), retried | `story_in_progress` |
| 7 | Script names Adamastor and Venus | Allowed: characters of the series' public-domain source (C9) | `story_in_progress` |
| 8 | Next episode does not exist yet when Ep.N reaches D1 | Teaser falls back to a season teaser, noted at Gate A (C5) | `awaiting_story_approval` |
| 9 | Recap on, previous episode not yet approved | Recap built from the previous story's approved script if it has one; otherwise flagged at Gate A to turn the recap off (C5) | `awaiting_story_approval` |
| 10 | Vision QA sees a realistic face for Vasco da Gama | Fails: historical figures only as approved kit characters (C3); scene rewritten | `visuals_in_progress` |
| 11 | Route map in Ep.05 shows stops from Ep.07 | Fails: route state comes from `route.yaml` up to the episode number; scene rewritten | `visuals_in_progress` |
| 12 | Narration runs 6 min 30 s | Flag: accept anyway or back to D1 to shorten | `needs_attention` |
| 13 | Ep.03 and a fable story in the same queue | Each runs its own steps by `format` and profile; one at a time | unchanged |
| 14 | Final review: "the chat bubbles in chapter 2 are too fast" | Routed to D3; only those scenes rewritten | `visuals_in_progress` |
| 15 | Episode published | Description lists sources and translation credits; 2–3 Shorts start (C8) | `published` |

## Status transitions

No new status values. Pipeline D's flow and transitions apply as written; the series only adds checks inside D1 (beat sheet, source grounding, claim kinds, verse text), D3 (route state, historical-figure rule) and D5 (sources, credits, chapters).

## Decisions (defaults, open to change)

- The series runs on Pipeline D; no new pipeline and no new status values.
- Everything is drawn by the kit in code; no image model, no video model (`illustrations: none`).
- Claims carry a kind (documented / tradition / legend), and legend is always framed as legend.
- Only the original Portuguese (public domain), our own translations, or pre-1929 translations (Mickle 1776, Aubertin 1878, Burton 1880), always credited.
- The poem's text is stored in the repo with its provenance, and Verse Moments quote it verbatim.
- The season route map is driven by a versioned series file, not by Claude.
- Ep.00 is the reference and is not re-made by the pipeline.

## Resolved conflicts

The owner accepted every proposed default on 2026-10-30 ("let's use your recommendations on all"). Each entry names the decision it changed; the affected docs now say the same thing.

### C1 — Landscape long-form on Pipeline D
**Conflicts with:** Pipeline D purpose, comparison table and D2/D4 rules (9:16, 30–60 s, 110–150 words, narration over 58 s flags, word captions, no chapters).
**Decision:** keep one `motion_picture` format and add a profile field `frame: vertical | landscape`, with `target_seconds` as a target plus an allowed range. A landscape profile switches the long-form rules on: 1920×1080 stage and safe zones, word count from the range, Stage 2's long-form pauses, Stage 4 subtitles and its 2.5–6 min tolerance instead of the 58 s cap, Stage 5 chapters and thumbnails. A separate format is not worth it: the steps are identical, only parameters differ, and a separate format would duplicate the dispatcher routes and the kit.

### C2 — A per-topic channel
**Conflicts with:** the overview ("One YouTube channel for both long videos and Shorts — do not split", "One fixed narrator voice across the whole channel", "One style for the whole channel", the kids-fable concept) and Pipeline D's decision "same YouTube channel as the fables; no per-topic channel setting".
**Decision:** a `channel` table, created on the management page: name, YouTube handle, TikTok and Instagram accounts, default narrator voice, watermark, title-sting / intro assets, closing line (text + approved audio), audience setting, default music moods. `topic.channel_id` is required and defaults to the fables channel, so existing topics are unchanged. The story snapshots the channel at Run. Stage 5 and B4 show which channel to upload to. Pipeline C and "clips from any video" already expect this field, so it is built once for all three. The overview's one-channel, one-narrator and one-style lines become "per channel" rules, with the fables channel keeping its current ones.

### C3 — Drawing long-dead historical figures
**Conflicts with:** Pipeline D "Real people are named in narration only, never drawn recognisably" and image QA scenario 9.
**Decision:** a narrow exception. A real person may be drawn recognisably only if all of these hold: they died before 1900; they are on the series' approved list (their character file has `historical: true` and `died`); they are drawn **only by kit code** as a stylised flat geometric character approved at Gate A; and they are shown with respect, never in a demeaning or sexualised way. AI-generated likenesses of any real person stay banned, living people stay banned, and photo-real or portrait-copy depictions stay banned. Vision QA checks that any human figure on screen is an approved kit character.

### C4 — The host's lines vs "no invented quotes"
**Conflicts with:** Pipeline D "Invented quotes from real people are never allowed".
**Decision:** Camões the cartoon host may speak (speech bubbles, pop-in lines) as the show's commentary, written in an obviously modern voice, and never presented as something the historical Camões said or wrote. Real quotes appear only in quotation marks or a Verse Moment, verbatim, with Canto and stanza (or the letter's source). The same applies to other historical figures: no invented lines in quotation marks. The narrator stays an unnamed voice, and Camões talks through on-screen bubbles. If Ep.00's source shows the narrator speaking as Camões in the first person, the series switches to "the narrator is the host", with the same commentary-not-quotes rule; that is a profile change, not a new decision.

### C5 — Series structure
**Conflicts with:** the overview and Stage 0 (topics and stories have no order beyond the queue, no continuity between stories, no source text).
**Decision:**
- `topic.is_series`; `story.season` and `story.episode_number`, unique per topic and season, shown on the queue and the story page.
- The queue stays the single order for Run. Running an episode whose previous episode is not yet `final_approved` asks for a confirmation (no hard block).
- **Recap** (optional, `story.recap_enabled`): D1 gets the previous episode's approved script summary and writes a ≤ 15 s "previously on".
- **Teaser:** D1 gets the next episode **by episode number** (not by queue position, which can change) — its title, concept and source excerpt — and writes the last 10 s. No next episode yet: a season teaser, noted at Gate A. If the next episode's concept changes after this episode is approved, nothing is regenerated automatically; the reviewer can send it back.
- **Source excerpt:** `story.source_excerpt` (Canto + stanza ranges) is required for a series topic that has a `source`. D1 receives those stanzas verbatim from `sources/`, and QA checks every chapter maps to stanzas or claims.

### C6 — One gate for a long-form script
**Conflicts with:** Pipeline D's reason for a single gate ("because the script is one minute long"); Pipeline A uses two gates for 3–5 minute videos.
**Decision:** keep D's single gate (script + cast + claims), plus the beat sheet mapping, the source excerpt and the translations. Regenerating a 9-minute script with `claude -p` is still cheap, and the beat sheet constrains the story enough that a separate outline gate would mostly repeat it. Update D's reason to "because the profile's structure fixes the story's shape".

### C7 — Intro and end card
**Conflicts with:** Pipeline D "No intro and no end card; watermark only" and Pipeline A "Channel intro and end card on every video".
**Decision:** no channel intro: the cold open comes first and the per-episode title sting (~3 s, rendered by the kit) acts as the intro. No separate end card: the 10 s teaser is the end, and its layout leaves room for YouTube's end-screen elements (subscribe + next video), which need at least 5 s. Pipeline A keeps its intro and end card. Pipeline D records this as the profile field `opening: none | title_sting`, with vertical profiles defaulting to `none`.

### C8 — Vertical Shorts from a code-rendered episode
**Conflicts with:** Pipeline D5 ("B3 builds the TikTok and Reels variants directly; no B1 selection") and B2 (re-generates vertical illustrations with Flux, storybook animation, fables closing line).
**Decision:** B1 selects 2–3 slices from the episode as for fables. For a `motion_picture` episode, B2 **re-renders the slice's scenes from the same scene code at 1080×1920**: the kit is frame-aware, and scene code lays out against the stage size, so one scene file renders in both frames. Vertical scene QA (safe zones, layout) runs on the re-render, and a scene that fails is rewritten as a vertical variant only. Word captions and the channel's closing line are added, then B3 and B4 as written. (Rejected alternative: Claude writes separate vertical scene code per slice, which means a simpler kit but double the scene writing.)

### C9 — Naming characters from a public-domain source
**Conflicts with:** CLAUDE.md "Prompts never name a studio, franchise or existing character" and D1/look-notes QA ("no existing character named"; notes naming one are refused).
**Decision:** the rule's aim (no copyrighted IP) stays, but characters and works in the **public domain** that are the topic's declared source (here: *Os Lusíadas*, the Greco-Roman gods, Adamastor, the Old Man of Restelo, the historical figures) are allowed by name in prompts, scripts and look notes. The topic stores its source and an allowlist of names; QA and the look-notes check read it. Studios, franchises and copyrighted characters (including modern adaptations of the same story) stay banned.

### C10 — Code-drawn characters without reference images
**Conflicts with:** the overview and Stage 1 (every character file has reference images drawn by Flux; Gate A shows a reference sheet with Redraw).
**Decision:** character files gain `drawn_by: flux | code` (default `flux`). A `code` character names its `kit_component` and pose list instead of reference images; Gate A shows a pose sheet rendered from the kit, and Redraw becomes "rewrite the component" with the reviewer's note. A new code character in a script needs its component written and approved at Gate A like a new Flux character. The roster distinctness check still runs on the visual description.

### C11 — Synthesised music and sound hits
**Conflicts with:** CLAUDE.md "Music only from the curated local library, each track stored with its license file" (Ep.00 and the motion test use original music synthesised by a script), and there is no sound-effects library in any doc. Also the mix level: Pipeline D's −27 LUFS was set for Shorts; Stage 4 uses −14 LUFS for long-form.
**Decision:** synthesised tracks and sound effects are imported into the library like any other track, with an "own work, generated by `music.py` vN" licence record and mood tags; assembly never generates audio on the fly. Add a small sound-effects library (flash hit, whoosh, stamp, ping, boom) under the same import and licence rules. Mix level: keep D's −27 LUFS default for this series too, since it matches the owner's listening; revisit if retention suffers.

### C12 — Third rejection archives an episode
**Conflicts with:** Stage 1 and Pipeline D ("3 rejections archive").
**Decision:** for a series topic, reaching a rejection limit sets `needs_attention` instead of `archived`, so a season never silently loses an episode. Kill still archives.

## Build order

1. Put Ep.00's source in `reference/lusiadas-ep00/` (done: `ep0.html`).
2. The `epic` profile (YAML, plus the new `frame` and `illustrations` fields) (done).
3. Landscape mode in `motion-kit/`, seeded from `ep0.html`, plus the series components above, working in both frames (done: kit 1.0.0, see `motion-kit/README.md`).
4. Series fields (C5), channel (C2), character file changes (C10), claim kinds and verses.
5. Source text in `sources/lusiadas/` and `series/lusiadas/route.yaml`.
6. Ep.01, "The Gods Have a Group Chat", as the first real run; judged by the owner from the video and its contact sheet before Ep.02 starts.

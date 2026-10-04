# Stage 2 — Narration / Audio

Stage 2 turns the approved, speaker-tagged script into narration audio: the fixed channel narrator plus each character in their own voice. It runs automatically after script approval, has no human gate, and verifies every line by transcribing it back and comparing it with the script.

## Position in the pipeline

- Pipeline: A (long-form). Stage 1 (Gate B approved) → **Stage 2** → Stage 3 (Visual Generation).
- Trigger: automatic when status becomes `script_approved`; status → `audio_in_progress`.
- Runs locally: the Spring app calls the local Python sidecar for speech generation (Kokoro) and transcription (Whisper).

## Inputs

| Input | Source |
| --- | --- |
| Approved script, every line tagged with a speaker | Stage 1 |
| Scene breakdown (which lines belong to which scene) | Stage 1 |
| Character voices (voice ID and settings) | Character files |
| Narrator voice | Channel config (one fixed voice) |
| Pronunciation dictionary | Channel config (e.g. character names) |
| Language | English for the MVP |

## Outputs

- **Per-line audio clips**, one per script line, in the speaker's voice.
- **Full narration track** stitched from the clips with configured pauses.
- **Timing manifest**: for every line — line ID, speaker, scene, start time, end time. Stage 3 uses it to size each scene, Stage 4 for sync and captions, Pipeline B to cut clips.

## Generation step

- Each line is generated separately with its speaker's voice, so a bad line can be redone without touching the rest.
- The pronunciation dictionary is applied to the text sent to the voice engine only; the script text itself never changes.
- Clips are normalized to a consistent loudness.
- Pauses are inserted when stitching: short between lines of the same speaker, longer on a speaker change, longest at a scene boundary. All three are config values.
- Clips are cached by a hash of (text, voice, settings, engine version): a re-run only regenerates lines whose inputs changed.
- The voice engine sits behind the `VoiceEngine` interface, so Kokoro can later be swapped for Chatterbox.

## Automated QA

### Per line

| Check | Pass condition |
| --- | --- |
| Transcript match | Whisper transcript vs. script line, after normalizing case, punctuation and numbers: about 95% word match or better |
| Speaking rate | Duration is plausible for the word count (catches garbled, rushed or stretched audio) |
| Silence | No long silent gap inside the clip; clip is not empty |
| Clipping | No distortion from clipped peaks |
| Voice | Clip was generated with the voice assigned to that speaker |

### Whole track

| Check | Pass condition |
| --- | --- |
| Completeness | Every script line has exactly one passing clip, in order |
| Duration | Total length within the tolerance around the story's target length |
| Manifest | Timing manifest covers every line and every scene, with no overlaps |

## Retry rules

- A failing line is regenerated automatically, up to **3 times per line**.
- If any line still fails after 3 attempts, the story goes to `needs_attention`, listing the failing lines and the reason. Passing lines are kept.
- The story is not archived here: its story and script are already approved and worth keeping.

## needs\_attention — human options

| Situation | Options on the page |
| --- | --- |
| A line keeps failing | Retry the line; add a pronunciation entry and retry; or send the story back to Gate B to regenerate the script with notes |
| Total duration out of tolerance | Accept anyway; or send back to Gate B to shorten or lengthen the script |
| Sidecar or engine unavailable | Resume once it is running again |

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | All lines pass, track stitched, manifest written; Stage 3 starts automatically | `audio_done` |
| 2 | One line fails the transcript match | That line regenerated (up to 3 times); others untouched | `audio_in_progress` |
| 3 | A line fails 3 times | Flag with failing lines listed | `needs_attention` |
| 4 | Character name keeps being mispronounced | Reviewer adds a pronunciation entry, retries the line | `audio_in_progress` |
| 5 | Total duration outside tolerance | Flag; reviewer accepts or sends back to Gate B | `needs_attention` |
| 6 | Reviewer sends story back to Gate B | Script regenerates with notes; script rejection counter increments; audio redone after re-approval | `script_in_progress` |
| 7 | Speaker in script has no voice assigned | Should be impossible after Gate A; if it happens, flag before generating anything | `needs_attention` |
| 8 | Python sidecar down or model fails to load | Backoff retries, not counted as line attempts; then flag | `needs_attention` |
| 9 | App restarts mid-stage | Cached passing clips are kept; only missing or failing lines regenerate | `audio_in_progress` |
| 10 | Narrator voice changed in config | Affects stories that reach Stage 2 afterwards; finished stories are untouched | unchanged |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `script_approved` | Automatic | `audio_in_progress` |
| `audio_in_progress` | All QA passes | `audio_done` → Stage 3 |
| `audio_in_progress` | Line fails 3 times, duration out of tolerance, missing voice, or sidecar failure | `needs_attention` |
| `needs_attention` | Retry, pronunciation fix, or accept | `audio_in_progress` |
| `needs_attention` | Send back to Gate B | `script_in_progress` |

## Logging

- Per line: text sent, voice, settings, engine version, each attempt's transcript and match score, QA results, duration.
- Per story: total duration, pause settings used, final manifest, and every status change with its reason.

## Decisions

- English narration; Kokoro (local, Apache 2.0) behind a swappable `VoiceEngine`; Chatterbox is the planned upgrade.
- One fixed narrator voice for the channel; characters use the voices assigned in their files.
- Lines generated and verified individually; transcript match about 95%; 3 automatic retries per line, then `needs_attention`.
- No human gate in Stage 2; final review in Stage 4 catches anything QA cannot hear.
- Pronunciation fixes change what the engine reads, never the approved script.

## Defaults confirmed

- Narrator voice is picked by listening to the Kokoro voice samples before the first run (setup task).
- Duration tolerance: 2.5 to 6 minutes for a 3–5 minute target.
- Pauses: 0.3 s same speaker, 0.6 s speaker change, 1.2 s scene boundary; tunable by ear.

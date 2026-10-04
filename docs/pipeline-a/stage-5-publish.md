# Stage 5 — Publish

Stage 5 prepares everything needed to publish the approved video on YouTube — search-optimized title, description, tags, chapters and thumbnails — and guides a human through a semi-manual upload. Marking the video as published triggers Pipeline B.

## Position in the pipeline

- Pipeline: A (long-form). Stage 4 (Final review approved) → **Stage 5** → Pipeline B.
- Trigger: automatic when status becomes `final_approved`; status → `preparing_publish`.
- MVP is **semi-manual**: the pipeline prepares a publish package; a human uploads it in YouTube Studio. Uploading via the YouTube API is deferred because new, unaudited API projects can only upload private videos.

## Inputs

| Input | Source |
| --- | --- |
| Final video and subtitle file | Stage 4 |
| Approved story, script, scene breakdown | Stage 1 |
| Timing manifest (for chapters) | Stage 2 |
| Shot images (for thumbnails) | Stage 3 |
| Topic (series) name | Stage 0 |
| Music used and any attribution text | Stage 4 |
| Channel audience setting | Channel config: **not made for kids** |

## Step 5A — Metadata generation (Claude)

Goal: make the most of YouTube's search and recommendation system while describing the video honestly.

- **Title** — curiosity-driven and searchable, roughly 50–60 characters so it isn't cut off; includes the main character or series name.
- **Description** — first two lines are a hook (they show in search); then a short story summary, the moral, chapter timestamps, the series playlist link, music attribution if any, and channel links.
- **Chapters** — one per scene from the timing manifest, starting at 0:00.
- **Tags and hashtags** — a focused set of tags plus up to 3 hashtags.
- **Playlist** — the topic's series playlist, so viewers binge the same universe.
- **Thumbnails** — 3 variants, each from a strong existing shot with a short text overlay (2–4 words) in the channel style, 1280×720. Three variants allow YouTube's thumbnail A/B testing.

### Automated QA

| Check | Pass condition |
| --- | --- |
| Title | Within YouTube's limit; target length met; no trademarked names or franchises |
| Honesty | Title and thumbnails reflect what actually happens in the video (no misleading clickbait) |
| Chapters | Start at 0:00; at least 3; each at least 10 seconds |
| Description | Hook present; required music attributions present; links valid |
| Tags | Within YouTube's total length limit |
| Thumbnails | Correct size and file size; text readable at small size; channel style |

Failures regenerate automatically, up to 3 times, then `needs_attention`.

## Step 5B — Publish package and checklist

Status `ready_to_upload`. The page shows a publish screen with copy buttons for every text field, downloads for the video, subtitles and thumbnails, and this checklist:

1. Upload the video in YouTube Studio as **private**.
2. Paste title, description, tags; set the audience to **not made for kids** (channel setting).
3. Upload the subtitle file and the thumbnail (or set up the 3-thumbnail test).
4. Add to the series playlist; add the end screen elements over the end card.
5. Wait for YouTube's copyright check to finish with no claims.
6. Publish, then paste the video URL back into the page and click **Mark as published**.

Metadata can be edited on the page before copying, or regenerated with notes.

## Scenarios

| # | Scenario | What happens | Resulting status |
| --- | --- | --- | --- |
| 1 | Happy path | Package prepared, uploaded, copyright check clean, URL pasted | `published`; Pipeline B triggered |
| 2 | Reviewer dislikes the title or thumbnails | Edit on the page, or regenerate with notes | `ready_to_upload` |
| 3 | Copyright check flags a music track | Reviewer clicks **Music claimed**: either dispute with the stored license file, or send back to Stage 4 with that track excluded; the corrected video returns to final review | `ready_to_upload` or `assembling` |
| 4 | Copyright check flags something visual | Treated as a final-review rejection with the claim as the reason; rejection analysis routes the fix | `analyzing_rejection` |
| 5 | Metadata QA fails 3 times | Flag | `needs_attention` |
| 6 | URL pasted is not a valid YouTube video URL | Refused with a message | unchanged |
| 7 | Video sits unpublished for a while | No timeout; listed under "ready to upload" | `ready_to_upload` |
| 8 | Reviewer decides not to publish after all | Kill from the publish screen | `archived` |

## Status transitions

| From | Event | To |
| --- | --- | --- |
| `final_approved` | Automatic | `preparing_publish` |
| `preparing_publish` | Metadata QA passes | `ready_to_upload` |
| `preparing_publish` | QA fails 3 times | `needs_attention` |
| `ready_to_upload` | Mark as published (valid URL) | `published` → Pipeline B |
| `ready_to_upload` | Music claimed, send back | `assembling` |
| `ready_to_upload` | Visual claim | `analyzing_rejection` |
| `ready_to_upload` | Kill | `archived` |

## Logging

- Generated metadata and every edit; thumbnails produced; checklist completion time; the YouTube URL; any copyright claim and how it was resolved.

## Decisions

- Semi-manual publishing for the MVP; no scheduling (the human publishes when ready). YouTube API upload is a later upgrade once the API project passes Google's audit.
- Metadata generated by Claude to perform well in search and recommendations, without misleading titles or thumbnails.
- 3 thumbnail variants per video for A/B testing.
- Upload private first; publish only after a clean copyright check.
- Audience: fixed channel-wide setting of **not made for kids**.

## Compliance note — audience setting

- The "made for kids" setting is a legal requirement (COPPA), not a monetization choice; it must reflect who the content is actually for. YouTube can override it.
- Channel position: content is written for **families watching together** — kid-safe, but aimed at older kids and adults as a general audience, not at young children. On that basis the channel uses **not made for kids**.
- Stage 1 enforces this in the story prompt and QA (audience fit check), so every video matches the setting.
- Metadata (titles, descriptions, thumbnails) presents the videos as family entertainment, consistent with the content.
- If the target audience shifts toward young children, the setting must change to made for kids. It is a channel config value, so no code change is needed. Review YouTube's audience guidance before launch; get legal advice if unsure.

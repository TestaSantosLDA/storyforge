# Tasks — Stage 5 (publish)

Spec: [stage-5](../pipeline-a/stage-5-publish.md).

## Done

- [x] Final approval moves straight on to `preparing_publish`
- [x] Metadata by Claude (`metadata-v1`): title, two-line hook, summary, moral, chapter titles, tags, up to 3 hashtags, 3 thumbnail picks (shot + 2–4 words)
- [x] Description assembled in code: hook, summary, moral, chapters, series playlist link, music attribution, channel links (`storyforge.channel.*`), hashtags
- [x] Chapters per scene from the manifest plus the intro, first at 0:00; scenes under 10 s merge; fewer than 3 → no chapters
- [x] Thumbnails drawn in code from passing shot images: 1280×720 JPEG under 2 MB, cream text with a dark outline
- [x] QA: title length, chapters, description size and hook, tag budget, thumbnails (code); honesty, trademarks, thumbnail legibility (Claude vision, `metadata-qa-v1`); 3 tries with feedback, then needs attention
- [x] Publish page: copy buttons, edit and save (new version), regenerate with notes, video / subtitle / thumbnail downloads, upload checklist
- [x] Mark as published with a validated YouTube URL; refused otherwise
- [x] Music claim: dispute with the license file (stays ready) or replace the track (back to assembly, track excluded)
- [x] Visual claim: recorded as a final rejection and routed by rejection analysis
- [x] Kill from the publish page
- [x] Tests: Stage 5 scenarios

## Open

- [ ] Pipeline B trigger on `published` (comes with Pipeline B)
- [ ] Fill in `storyforge.channel.links` and `storyforge.channel.playlists` once the channel exists
- [ ] YouTube API upload (later, after Google's audit)

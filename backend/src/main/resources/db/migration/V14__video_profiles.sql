-- Pipeline D video profiles (docs/pipeline-d/README.md "Video profiles", "Data changes").
-- V12 and V13 are taken by the clips branch and the story.format column.

alter table topic
    -- Profile file id in channel/video-profiles/; null for fable topics.
    add column profile_id        text,
    -- Topic overrides of profile fields, snake_case keys as in the YAML file.
    add column profile_overrides jsonb,
    add column look_notes        text;

alter table story
    add column look_notes       text,
    -- Profile, overrides and look notes captured at Run, with the profile file's version and hash.
    add column profile_snapshot jsonb;

-- Pipelines C and D share the story table, queue and statuses with Pipeline A; the format tells them apart
-- (docs/pipeline-c/README.md, docs/pipeline-d/README.md). Existing stories are fables. V12 is the clips migration.
alter table story
    add column format text not null default 'fable'
        check (format in ('fable', 'story_short', 'motion_picture'));

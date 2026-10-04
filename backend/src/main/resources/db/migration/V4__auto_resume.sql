-- When a story is flagged because Claude's usage limit was hit, it resumes on its own at this time.
alter table story add column auto_resume_at timestamptz;
alter table story add constraint story_auto_resume_only_when_flagged
    check (auto_resume_at is null or status = 'needs_attention');

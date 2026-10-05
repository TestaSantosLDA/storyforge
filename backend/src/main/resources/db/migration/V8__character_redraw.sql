-- Gate A "redraw this character": a new draft version carries the request; the old one is superseded.
alter table story_draft add column redraw_notes jsonb;
alter table story_draft drop constraint story_draft_review_state_check;
alter table story_draft add constraint story_draft_review_state_check
    check (review_state in ('pending', 'approved', 'rejected', 'abandoned', 'superseded'));

-- A pending draft is abandoned when its story is archived, so a restored story starts fresh at Step 1A.
alter table story_draft drop constraint story_draft_review_state_check;
alter table story_draft add constraint story_draft_review_state_check
    check (review_state in ('pending', 'approved', 'rejected', 'abandoned'));
update story_draft d set review_state = 'abandoned'
  from story s where s.id = d.story_id and s.status = 'archived' and d.review_state = 'pending';

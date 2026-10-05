-- Reference sheets generated for each new character of a story draft (Step 1A), with their QA results:
-- {"<character id>": {"front": "<asset key>", "side": "...", "expressions": "...", "checks": [...], "passed": true}}
alter table story_draft add column sheets jsonb;

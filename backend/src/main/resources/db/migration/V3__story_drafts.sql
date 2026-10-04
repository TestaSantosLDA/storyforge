-- Stage 1 outputs. Every attempt that passed QA is kept as a numbered version, never overwritten.
create table story_draft (
    id             bigserial primary key,
    story_id       bigint      not null references story (id),
    kind           text        not null check (kind in ('story', 'script')),
    version        integer     not null,
    content        jsonb       not null,
    qa_results     jsonb,
    prompt_version text,
    review_state   text        not null default 'pending' check (review_state in ('pending', 'approved', 'rejected')),
    reviewer_notes text,
    created_at     timestamptz not null default now(),
    unique (story_id, kind, version)
);

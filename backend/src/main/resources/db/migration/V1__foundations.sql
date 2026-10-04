-- Foundations schema. Status values are the lowercase names from CLAUDE.md.

create table topic (
    id          bigserial primary key,
    name        text        not null unique,
    description text        not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table story (
    id                 bigserial primary key,
    topic_id           bigint      not null references topic (id),
    concept            text        not null,
    moral              text        not null,
    target_length_min  integer     not null default 4 check (target_length_min between 3 and 5),
    notes              text,
    -- Meaningful only while queued; null otherwise.
    queue_position     integer,
    status             text        not null,
    -- Where Resume goes back to; set when the story enters needs_attention.
    resume_status      text,
    attention_reason   text,
    archive_reason     text,
    -- Topic description captured at Run, so later topic edits don't change an in-flight story.
    topic_snapshot     text,
    story_qa_retries   integer     not null default 0,
    story_rejections   integer     not null default 0,
    script_qa_retries  integer     not null default 0,
    script_rejections  integer     not null default 0,
    final_rejections   integer     not null default 0,
    cost_usd           numeric(12, 4) not null default 0,
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint story_queue_position_only_when_queued
        check ((status = 'queued') = (queue_position is not null)),
    constraint story_resume_only_when_flagged
        check ((status = 'needs_attention') = (resume_status is not null)),
    -- Deferred so a reorder can shift positions inside one transaction. Nulls never clash.
    constraint story_queue_position_uq unique (queue_position) deferrable initially deferred
);

create index story_status_idx on story (status);

-- Every status change, with who/what triggered it and why (rule 8).
create table status_change (
    id           bigserial primary key,
    story_id     bigint      not null references story (id),
    from_status  text,
    to_status    text        not null,
    triggered_by text        not null,
    reason       text,
    changed_at   timestamptz not null default now()
);

create index status_change_story_idx on status_change (story_id, changed_at);

-- Every attempt of every AI or engine step (rule 8).
create table attempt (
    id             bigserial primary key,
    story_id       bigint      not null references story (id),
    stage          text        not null,
    step           text        not null,
    attempt_no     integer     not null,
    prompt_version text,
    model          text,
    engine_version text,
    input_hash     text,
    inputs         jsonb,
    raw_output     text,
    qa_results     jsonb,
    counters       jsonb,
    cost_usd       numeric(12, 4) not null default 0,
    outcome        text        not null,
    error          text,
    started_at     timestamptz not null,
    finished_at    timestamptz
);

create index attempt_story_idx on attempt (story_id, stage, step);

-- One row per GPU. Pipelines A and B never use the GPU at once; a crashed holder's lease expires.
create table gpu_lease (
    gpu_id      text primary key,
    holder      text,
    acquired_at timestamptz,
    expires_at  timestamptz
);

insert into gpu_lease (gpu_id) values ('gpu0');

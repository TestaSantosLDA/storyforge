-- Pipeline B, clips from any video. Status values are the lowercase names from CLAUDE.md.

-- One row per distinct video (by content hash); a resubmission reuses its transcript.
create table source_video (
    id            bigserial primary key,
    origin        text           not null check (origin in ('upload', 'youtube')),
    url           text,
    file_key      text           not null,
    content_hash  text           not null unique,
    title         text           not null,
    duration_sec  numeric(10, 3) not null,
    width         integer        not null,
    height        integer        not null,
    language      text,
    -- [{line_id, start, end, text}], the shape of the Stage 2 timing manifest, and the words behind it.
    manifest      jsonb,
    words         jsonb,
    scene_cuts    jsonb,
    transcriber   text,
    yt_dlp_version text,
    created_at    timestamptz    not null default now()
);

create table clip_job (
    id               bigserial primary key,
    story_id         bigint references story (id),
    source_video_id  bigint references source_video (id),
    status           text        not null,
    resume_status    text,
    attention_reason text,
    -- What the form sent, until intake turns it into a source_video.
    url              text,
    upload_key       text,
    upload_name      text,
    source_text      text        not null,
    own_video        boolean     not null,
    channel          text        not null,
    target_clip_count integer    not null default 3 check (target_clip_count between 1 and 5),
    notes            text,
    spoilers_allowed boolean     not null default false,
    reframe_mode     text        not null default 'blurred_fill',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    -- A job comes from a story or a video, never both. A video job has neither until intake links its source.
    constraint clip_job_one_source check (num_nonnulls(story_id, source_video_id) <= 1),
    constraint clip_job_resume_only_when_flagged check ((status = 'needs_attention') = (resume_status is not null))
);

create index clip_job_status_idx on clip_job (status);

create table clip (
    id             bigserial primary key,
    job_id         bigint         not null references clip_job (id) on delete cascade,
    index_in_job   integer        not null,
    status         text           not null,
    start_line_id  text           not null,
    end_line_id    text           not null,
    start_sec      numeric(10, 3) not null,
    end_sec        numeric(10, 3) not null,
    hook_title     text           not null,
    reframe_mode   text           not null,
    crop_offset    numeric(4, 3),
    video_key      text,
    duration_sec   numeric(8, 3),
    qa             jsonb,
    rejections     integer        not null default 0,
    rework_notes   text,
    created_at     timestamptz    not null default now(),
    unique (job_id, index_in_job)
);

create table clip_variant (
    clip_id    bigint  not null references clip (id) on delete cascade,
    platform   text    not null check (platform in ('youtube', 'tiktok', 'instagram')),
    caption    text    not null,
    hashtags   text    not null,
    cta        text    not null,
    cover_key  text    not null,
    skipped    boolean not null default false,
    post_url   text,
    primary key (clip_id, platform)
);

create table clip_rejection (
    id         bigserial primary key,
    clip_id    bigint      not null references clip (id) on delete cascade,
    reason     text        not null,
    target     text,
    notes      text,
    created_at timestamptz not null default now()
);

-- Every status change of a job or one of its clips, with who/what triggered it and why (rule 8).
create table clip_status_change (
    id           bigserial primary key,
    job_id       bigint      not null references clip_job (id) on delete cascade,
    clip_id      bigint,
    from_status  text,
    to_status    text        not null,
    triggered_by text        not null,
    reason       text,
    changed_at   timestamptz not null default now()
);

create index clip_status_change_job_idx on clip_status_change (job_id, changed_at);

-- Attempts belong to a story or to a clip job.
alter table attempt alter column story_id drop not null;
alter table attempt add column clip_job_id bigint references clip_job (id);
alter table attempt add constraint attempt_one_owner check (num_nonnulls(story_id, clip_job_id) = 1);
create index attempt_clip_job_idx on attempt (clip_job_id, step);

-- Stage 4. The curated music library: every track keeps its license proof (docs/pipeline-a/stage-4 "Music library").
create table music_track (
    id               bigserial primary key,
    title            text        not null,
    asset_key        text        not null,
    duration_sec     numeric(8, 3) not null,
    moods            text[]      not null,
    source_url       text        not null,
    license_type     text        not null,
    license_key      text        not null,
    attribution      text,
    created_at       timestamptz not null default now()
);

-- Each assembled cut of a story, with its music choices and QA. The latest one is what final review shows.
create table assembly (
    id           bigserial primary key,
    story_id     bigint      not null references story (id),
    version      integer     not null,
    video_key    text,
    srt_key      text,
    duration_sec numeric(8, 3),
    intro_sec    numeric(8, 3) not null default 0,
    music        jsonb,
    qa           jsonb,
    passed       boolean     not null,
    created_at   timestamptz not null default now(),
    unique (story_id, version)
);

-- Final review rejections, Claude's correction plan, and what was redone.
create table final_rejection (
    id          bigserial primary key,
    story_id    bigint      not null references story (id),
    assembly_id bigint      references assembly (id),
    reason      text        not null,
    plan        jsonb,
    outcome     text,
    created_at  timestamptz not null default now()
);

-- Reviewer instructions carried into later attempts: a shot's redraw note, or the story's mix settings.
create table story_note (
    story_id   bigint      not null references story (id),
    target     text        not null,
    note       text        not null,
    created_at timestamptz not null default now(),
    primary key (story_id, target)
);

-- A final-review correction replaces earlier work: superseded images and clips are never reused.
alter table shot_image add column superseded boolean not null default false;
alter table audio_line add column superseded boolean not null default false;

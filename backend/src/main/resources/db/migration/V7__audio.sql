-- Stage 2. Every generated line attempt, with its transcript and QA; passing clips are reused by input hash.
create table audio_line (
    id           bigserial primary key,
    story_id     bigint      not null references story (id),
    line_id      text        not null,
    attempt      integer     not null,
    input_hash   text        not null,
    asset_key    text        not null,
    voice        text        not null,
    speed        numeric(4, 2) not null,
    engine_text  text        not null,
    engine       text,
    transcript   text,
    match_score  numeric(5, 4),
    duration_sec numeric(8, 3),
    qa           jsonb,
    passed       boolean     not null,
    created_at   timestamptz not null default now()
);

create index audio_line_story_idx on audio_line (story_id, line_id);
create index audio_line_hash_idx on audio_line (input_hash) where passed;

-- The stitched narration and its timing manifest (one per story; replaced when audio is redone).
create table audio_track (
    story_id          bigint primary key references story (id),
    asset_key         text           not null,
    manifest          jsonb          not null,
    duration_sec      numeric(8, 3)  not null,
    duration_accepted boolean        not null default false,
    created_at        timestamptz    not null default now()
);

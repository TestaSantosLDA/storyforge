-- Stage 3. The shot plan is a story_draft of kind 'shots' (no human gate, stored approved).
alter table story_draft drop constraint story_draft_kind_check;
alter table story_draft add constraint story_draft_kind_check check (kind in ('story', 'script', 'shots'));

-- Every drawn shot image with its vision QA. One may be accepted by a reviewer after failing QA.
create table shot_image (
    id          bigserial primary key,
    story_id    bigint      not null references story (id),
    shot_id     text        not null,
    attempt     integer     not null,
    input_hash  text        not null,
    asset_key   text        not null,
    prompt      text        not null,
    seed        bigint      not null,
    engine      text,
    qa          jsonb,
    failures    integer     not null,
    passed      boolean     not null,
    accepted    boolean     not null default false,
    created_at  timestamptz not null default now()
);
create index shot_image_story_idx on shot_image (story_id, shot_id);

-- Animated clip per shot.
create table shot_clip (
    id           bigserial primary key,
    story_id     bigint        not null references story (id),
    shot_id      text          not null,
    input_hash   text          not null,
    asset_key    text          not null,
    duration_sec numeric(8, 3) not null,
    qa           jsonb,
    passed       boolean       not null,
    created_at   timestamptz   not null default now()
);
create index shot_clip_story_idx on shot_clip (story_id, shot_id);

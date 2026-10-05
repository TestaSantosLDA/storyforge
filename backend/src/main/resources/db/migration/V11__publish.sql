-- Stage 5. Each prepared publish package (metadata, thumbnails, QA); the latest is shown on the publish screen.
create table publish_package (
    id          bigserial primary key,
    story_id    bigint      not null references story (id),
    version     integer     not null,
    metadata    jsonb       not null,
    thumbnails  jsonb       not null,
    qa          jsonb,
    passed      boolean     not null,
    notes       text,
    created_at  timestamptz not null default now(),
    unique (story_id, version)
);

-- The published video, and any copyright claim and how it was resolved.
create table publication (
    story_id     bigint primary key references story (id),
    youtube_url  text        not null,
    published_at timestamptz not null default now()
);

create table copyright_claim (
    id          bigserial primary key,
    story_id    bigint      not null references story (id),
    kind        text        not null check (kind in ('music', 'visual')),
    detail      text        not null,
    resolution  text        not null,
    created_at  timestamptz not null default now()
);

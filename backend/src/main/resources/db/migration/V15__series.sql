-- Channels and series fields (docs/series/lusiadas.md "Data changes", decisions C2 and C5).

create table channel (
    id                     bigserial primary key,
    name                   text        not null unique,
    -- Exactly one channel is the default; new topics and every existing topic start on it.
    is_default             boolean     not null default false,
    youtube_handle         text,
    tiktok_account         text,
    instagram_account      text,
    narrator_voice         text,
    -- Asset keys (keep-forever retention), not repo files.
    watermark_key          text,
    title_sting_key        text,
    closing_line_text      text,
    closing_line_audio_key text,
    made_for_kids          boolean     not null default false,
    -- Default music moods for the channel's videos, a JSON list.
    music_moods            jsonb,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
create unique index channel_one_default on channel (is_default) where is_default;

insert into channel (name, is_default, closing_line_text)
values ('Fables', true, 'Watch the rest of the episode on our channel!');

alter table topic
    add column channel_id     bigint references channel (id),
    add column is_series      boolean not null default false,
    -- Folder name under sources/ holding the topic's public-domain source text; null when there is none.
    add column source_id      text,
    -- Names from the public-domain source allowed in prompts, scripts and look notes (C9), a JSON list.
    add column name_allowlist jsonb;
update topic set channel_id = (select id from channel where is_default);
alter table topic alter column channel_id set not null;

alter table story
    add column season           integer,
    add column episode_number   integer,
    -- JSON list of {canto, from_stanza, to_stanza}.
    add column source_excerpt   jsonb,
    add column recap_enabled    boolean not null default false,
    -- The topic's channel captured at Run.
    add column channel_snapshot jsonb;
create unique index story_episode_unique on story (topic_id, season, episode_number) where episode_number is not null;

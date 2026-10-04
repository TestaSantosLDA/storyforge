-- Stage 0 logging: every topic and story create, edit, delete, reorder, run and restore, with before/after values.
create table entity_change (
    id           bigserial primary key,
    entity_type  text        not null,
    entity_id    bigint      not null,
    action       text        not null,
    before_value jsonb,
    after_value  jsonb,
    triggered_by text        not null,
    changed_at   timestamptz not null default now()
);

create index entity_change_entity_idx on entity_change (entity_type, entity_id, changed_at);

create table users (
    id            uuid        not null primary key,
    email         text        not null,
    password_hash text        not null,
    display_name  text,
    created_at    timestamptz not null,
    constraint uq_users_email unique (email)
);

create table projects (
    id         uuid        not null primary key,
    owner_id   uuid        not null,
    key        text        not null,
    name       text        not null,
    created_at timestamptz not null,
    constraint uq_projects_key unique (key),
    constraint ck_projects_key_format check (key ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    constraint fk_projects_owner foreign key (owner_id) references users (id) on delete restrict
);

create table environments (
    id              uuid        not null primary key,
    project_id      uuid        not null,
    key             text        not null,
    name            text        not null,
    ruleset_version bigint      not null default 0,
    created_at      timestamptz not null,
    constraint uq_environments_project_key unique (project_id, key),
    constraint ck_environments_key_format check (key ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    constraint fk_environments_project foreign key (project_id) references projects (id) on delete cascade
);

create table flags (
    id                  uuid        not null primary key,
    project_id          uuid        not null,
    key                 text        not null,
    name                text        not null,
    description         text,
    client_side_visible boolean     not null,
    archived_at         timestamptz,
    created_at          timestamptz not null,
    constraint uq_flags_project_key unique (project_id, key),
    constraint ck_flags_key_format check (key ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    constraint fk_flags_project foreign key (project_id) references projects (id) on delete cascade
);

create table api_keys (
    id             uuid        not null primary key,
    environment_id uuid        not null,
    key_hash       text        not null,
    key_prefix     text        not null,
    key_type       text        not null,
    name           text        not null,
    last_used_at   timestamptz,
    revoked_at     timestamptz,
    created_at     timestamptz not null,
    constraint uq_api_keys_key_hash unique (key_hash),
    constraint ck_api_keys_key_type check (key_type in ('server', 'client')),
    constraint fk_api_keys_environment foreign key (environment_id) references environments (id) on delete cascade
);

create table flag_configs (
    id                   uuid        not null primary key,
    flag_id              uuid        not null,
    environment_id       uuid        not null,
    enabled              boolean     not null,
    off_value            boolean     not null default false,
    fallthrough_value    boolean     not null,
    rollout_basis_points integer     not null,
    rollout_salt         text        not null,
    version              bigint      not null default 1,
    updated_at           timestamptz not null,
    constraint uq_flag_configs_flag_environment unique (flag_id, environment_id),
    constraint ck_flag_configs_rollout_basis_points check (rollout_basis_points between 0 and 10000),
    constraint ck_flag_configs_rollout_salt_format check (rollout_salt ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    constraint fk_flag_configs_flag foreign key (flag_id) references flags (id) on delete cascade,
    constraint fk_flag_configs_environment foreign key (environment_id) references environments (id) on delete cascade
);

create index ix_flag_configs_environment on flag_configs (environment_id);

create table targeting_rules (
    id             uuid    not null primary key,
    flag_config_id uuid    not null,
    priority       integer not null,
    attribute      text    not null,
    operator       text    not null,
    match_values   jsonb   not null,
    result_value   boolean not null,
    constraint uq_targeting_rules_config_priority unique (flag_config_id, priority),
    constraint ck_targeting_rules_operator check (
        operator in ('EQUALS', 'NOT_EQUALS', 'IN', 'NOT_IN', 'CONTAINS', 'STARTS_WITH', 'ENDS_WITH')
    ),
    constraint fk_targeting_rules_flag_config foreign key (flag_config_id) references flag_configs (id) on delete cascade
);

create table user_overrides (
    id             uuid    not null primary key,
    flag_config_id uuid    not null,
    user_key       text    not null,
    value          boolean not null,
    constraint uq_user_overrides_config_user unique (flag_config_id, user_key),
    constraint fk_user_overrides_flag_config foreign key (flag_config_id) references flag_configs (id) on delete cascade
);

create table audit_entries (
    id             uuid        not null primary key,
    project_id     uuid        not null,
    environment_id uuid,
    flag_id        uuid,
    actor_id       uuid        not null,
    action         text        not null,
    previous_value jsonb,
    new_value      jsonb,
    created_at     timestamptz not null,
    constraint ck_audit_entries_action check (
        action in (
            'project.created',
            'environment.created', 'environment.deleted',
            'flag.created', 'flag.updated', 'flag.archived', 'flag.restored',
            'config.updated', 'rules.replaced', 'overrides.replaced',
            'key.created', 'key.revoked'
        )
    ),
    constraint fk_audit_entries_project foreign key (project_id) references projects (id) on delete restrict,
    constraint fk_audit_entries_actor foreign key (actor_id) references users (id) on delete restrict,
    constraint fk_audit_entries_environment foreign key (environment_id) references environments (id) on delete set null,
    constraint fk_audit_entries_flag foreign key (flag_id) references flags (id) on delete set null
);

create index ix_audit_entries_project_created on audit_entries (project_id, created_at desc, id desc);
create index ix_audit_entries_flag_created on audit_entries (flag_id, created_at desc, id desc);

-- FR-PRJ-002 / FR-FLG-002: key is immutable once a project or flag is created.
create function reject_key_change() returns trigger as $$
begin
    if new.key <> old.key then
        raise exception 'key is immutable: cannot change % to %', old.key, new.key;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_projects_key_immutable
    before update on projects
    for each row execute function reject_key_change();

create trigger trg_flags_key_immutable
    before update on flags
    for each row execute function reject_key_change();

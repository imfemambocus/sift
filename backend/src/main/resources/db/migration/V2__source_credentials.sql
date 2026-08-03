create table source_credentials (
    id                uuid        not null,
    user_id           uuid        not null,
    source            text        not null,
    credential_type   text        not null,
    instance_url      text        not null,
    access_token_enc  text        not null,
    refresh_token_enc text,
    expires_at        timestamptz,
    last_sync_at      timestamptz,
    last_sync_status  text        not null,
    last_error        text,
    created_at        timestamptz not null,
    constraint source_credentials_pk primary key (id),
    constraint source_credentials_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint source_credentials_user_source_uk unique (user_id, source)
);

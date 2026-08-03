create table feed_items (
    id                uuid        not null,
    user_id           uuid        not null,
    source            text        not null,
    source_id         text        not null,
    kind              text        not null,
    priority          text        not null,
    title             text        not null,
    body              text,
    actor_name        text,
    actor_avatar_url  text,
    context_label     text,
    context_url       text,
    url               text        not null,
    source_created_at timestamptz not null,
    first_seen_at     timestamptz not null,
    last_seen_at      timestamptz not null,
    read_at           timestamptz,
    notified_at       timestamptz,
    resolved_at       timestamptz,
    raw_payload       jsonb,
    constraint feed_items_pk primary key (id),
    constraint feed_items_user_fk foreign key (user_id) references users (id) on delete cascade,
    -- user_id belongs in this key. two people on the same projects legitimately both hold a row
    -- for the same merge request, and without it one user's sync would clobber the other's.
    constraint feed_items_identity_uk unique (user_id, source, source_id)
);

-- the feed is always read newest first for one user, optionally narrowed to one source
create index feed_items_user_created_idx on feed_items (user_id, source_created_at desc);

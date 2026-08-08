-- how far through a mailbox Sift has read. one row per user, because a person has one Gmail
-- connection, and it survives a disconnect on purpose: reconnecting then picks up where it stopped
-- rather than announcing the whole window a second time. that is the same bet the gitlab watch
-- tables make.
create table gmail_sync_state (
    user_id           uuid        primary key references users (id) on delete cascade,
    newest_message_at timestamptz not null,
    updated_at        timestamptz not null
);

-- Tracking state for "something moved on a thing I am part of". Named for GitLab rather than
-- pretending to be generic: the identity of a resource here is a project id plus an iid, which is
-- GitLab's shape. A future source watches its own things its own way.

create table gitlab_watched_resources (
    id              uuid        not null,
    user_id         uuid        not null,
    resource_type   text        not null,
    project_id      bigint      not null,
    resource_iid    bigint      not null,
    title           text        not null,
    web_url         text        not null,
    -- what the resource looked like last time, so a sweep can tell whether to spend a request on
    -- its discussions at all
    last_updated_at timestamptz,
    last_sha        text,
    first_seen_at   timestamptz not null,
    constraint gitlab_watched_resources_pk primary key (id),
    constraint gitlab_watched_resources_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint gitlab_watched_resources_key unique (user_id, resource_type, project_id, resource_iid)
);

create table gitlab_watched_discussions (
    id            uuid        not null,
    user_id       uuid        not null,
    discussion_id text        not null,
    last_note_id  bigint      not null,
    first_seen_at timestamptz not null,
    constraint gitlab_watched_discussions_pk primary key (id),
    constraint gitlab_watched_discussions_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint gitlab_watched_discussions_key unique (user_id, discussion_id)
);

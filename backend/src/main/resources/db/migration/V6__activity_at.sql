/*
 * When the thing last moved, as opposed to when it was created.
 *
 * The feed was ordered and timestamped by source_created_at, which for a merge request row is when
 * the merge request was opened. So an MR from last week stayed at "Tuesday" and stayed near the
 * bottom no matter how many commits and replies landed on it today, which is the opposite of useful.
 */
alter table feed_items
    add column activity_at timestamptz;

update feed_items set activity_at = source_created_at where activity_at is null;

alter table feed_items
    alter column activity_at set not null;

drop index feed_items_user_created_idx;

create index feed_items_user_activity_idx on feed_items (user_id, activity_at desc);

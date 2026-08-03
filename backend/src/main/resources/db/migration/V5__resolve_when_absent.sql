/*
 * Whether "the source stopped mentioning this" means "it is dealt with".
 *
 * True for state: a to-do that is gone has been done, a merge request that is gone has been merged.
 * False for events: a reply arrived once, and it not arriving again on the next sweep says nothing.
 * Without this the sweep resolved every participation row minutes after creating it, so a comment
 * would appear and silently disappear before anyone looked at it.
 */
alter table feed_items
    add column resolve_when_absent boolean not null default true;

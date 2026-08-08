/*
 * A mailbox is read to its beginning, so reading it needs two edges rather than one.
 * newest_message_at is how far forward Sift has read and oldest_message_at how far back, and the
 * stretch between them is one unbroken run. backfill_done latches when nothing older is left.
 *
 * The search is the reason mail is in Sift, and a search can only find what was read, so how far
 * back the mailbox goes is a property of the source rather than something a deployment sets.
 */
alter table gmail_sync_state
    add column oldest_message_at timestamptz,
    add column backfill_done     boolean not null default false;

/*
 * A mailbox already read forward from some starting point has a floor: the oldest message it holds.
 * The walk back starts there rather than at the beginning of the mailbox, so nothing already read is
 * read a second time.
 */
update gmail_sync_state s
   set oldest_message_at = (select min(f.activity_at)
                              from feed_items f
                             where f.user_id = s.user_id
                               and f.source = 'GMAIL')
 where oldest_message_at is null;

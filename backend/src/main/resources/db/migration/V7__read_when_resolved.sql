-- resolved rows are part of the feed, so anything that finished upstream before it was ever opened
-- would sit there unread for ever and inflate the unread count. finishing is what dealt with it, so
-- it takes the moment it resolved as the moment it was read. FeedSyncStore applies the same rule to
-- every row that resolves; this fills in the rows that already exist.
update feed_items
   set read_at = resolved_at
 where resolved_at is not null
   and read_at is null;

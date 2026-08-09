/*
 * The only rows a sweep's silence can resolve: state the source keeps reporting, not yet finished.
 *
 * A sweep needs them and the rows it is about to write, and nothing else. Without this index, finding
 * them means walking every row of that user and source, which for a mailbox is thousands of rows read
 * on every sweep to answer "none of them".
 */
create index feed_items_resolvable_idx
    on feed_items (user_id, source)
 where resolve_when_absent and resolved_at is null;

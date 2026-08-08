/*
 * The feed pages over groups rather than over items, so a merge request's four rows cannot be split
 * across a page boundary. The database is what does that grouping, so the key is a column rather
 * than something computed in the response.
 *
 * GroupKeys.of owns the rule. The expression below is the same rule written once in SQL, to fill in
 * the rows that already exist.
 */
alter table feed_items
    add column group_key text;

update feed_items
   set group_key = lower(source) || ':' || split_part(url, '#', 1)
 where group_key is null;

alter table feed_items
    alter column group_key set not null;

create index feed_items_user_group_idx on feed_items (user_id, group_key);

/*
 * levenshtein, for the search.
 *
 * A word of the row matches when the query word is inside it, or when it is one edit away, which is
 * what forgives a single typo per word. fuzzystrmatch is a contrib module of the standard Postgres
 * image, so this needs no extra package; it does need the migration to run as an owner who may
 * create one.
 */
create extension if not exists fuzzystrmatch;

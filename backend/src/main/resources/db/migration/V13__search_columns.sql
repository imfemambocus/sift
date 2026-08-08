/*
 * The search reads every row of a feed, and a mailbox makes that feed large. Two costs dominated it,
 * and both were per row and per query: building the haystack, and splitting it into words.
 *
 * Both are stored now, so each is computed once when a row is written.
 *
 * There is no index here, and that is measured rather than assumed. A trigram index serves one
 * literal `like '%word%'` well, but the search asks that every word of a query match, and that each
 * one may match either as a substring or within an edit or two. The planner cannot reach an index
 * through that, and it takes a sequential scan whether or not one exists.
 *
 * The two expressions are the same text written twice because a generated column may not read
 * another generated column.
 */
alter table feed_items
    add column search_text text generated always as (
        lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
              coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
              replace(kind, '_', ' '))
    ) stored;

alter table feed_items
    add column search_words text[] generated always as (
        regexp_split_to_array(
            lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
                  coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
                  replace(kind, '_', ' ')),
            '[^[:alnum:]]+')
    ) stored;

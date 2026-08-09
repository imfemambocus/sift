/*
 * What came with an item, by file name, so a search finds a message by what was attached to it.
 *
 * One text column and not text[]: the two search columns below are generated, a generated column may
 * only use an immutable expression, and flattening an array into text is not one. The names are
 * separated by a newline, because a file name may contain a space.
 *
 * A generated column's expression cannot be altered, so both are dropped and written again. They
 * repeat each other for the reason they already did: a generated column may not read another one.
 */
alter table feed_items
    add column attachments text;

alter table feed_items
    drop column search_text;

alter table feed_items
    drop column search_words;

alter table feed_items
    add column search_text text generated always as (
        lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
              coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
              replace(kind, '_', ' ') || ' ' ||
              replace(coalesce(attachments, ''), E'\n', ' '))
    ) stored;

alter table feed_items
    add column search_words text[] generated always as (
        regexp_split_to_array(
            lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
                  coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
                  replace(kind, '_', ' ') || ' ' ||
                  replace(coalesce(attachments, ''), E'\n', ' ')),
            '[^[:alnum:]]+')
    ) stored;

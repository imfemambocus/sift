/*
 * More of a source's own text in the haystack, searched and never shown. A mail row carries the
 * snippet, which is about a hundred characters, so a message could not be found by anything it says
 * further in than that. The adapter bounds what it stores, which is what keeps a search over a whole
 * mailbox affordable: the table holds a prefix of each message and not the message.
 *
 * A generated column's expression cannot be altered, so both are dropped and written again. They
 * repeat each other for the reason they already did: a generated column may not read another one.
 */
alter table feed_items
    add column search_extra text;

alter table feed_items
    drop column search_text;

alter table feed_items
    drop column search_words;

alter table feed_items
    add column search_text text generated always as (
        lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
              coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
              replace(kind, '_', ' ') || ' ' ||
              replace(coalesce(attachments, ''), E'\n', ' ') || ' ' ||
              coalesce(search_extra, ''))
    ) stored;

alter table feed_items
    add column search_words text[] generated always as (
        regexp_split_to_array(
            lower(coalesce(title, '') || ' ' || coalesce(body, '') || ' ' ||
                  coalesce(context_label, '') || ' ' || coalesce(actor_name, '') || ' ' ||
                  replace(kind, '_', ' ') || ' ' ||
                  replace(coalesce(attachments, ''), E'\n', ' ') || ' ' ||
                  coalesce(search_extra, '')),
            '[^[:alnum:]]+')
    ) stored;

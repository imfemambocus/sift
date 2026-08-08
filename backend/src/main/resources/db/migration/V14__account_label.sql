/*
 * Which account at the source a credential belongs to, so Settings can name the mailbox or the user
 * rather than the host. Both adapters already read this at the start of every sweep, so it costs no
 * extra call; it is nullable because a credential has it only once a sweep has run.
 */
alter table source_credentials
    add column account_label text;

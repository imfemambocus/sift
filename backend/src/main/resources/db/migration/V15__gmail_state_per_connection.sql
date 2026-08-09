/*
 * How much of a mailbox Sift has read belongs to the connection, not to the person.
 *
 * Disconnecting a source deletes every row of it, so state that outlives the connection claims a
 * mailbox has been read whose rows are gone: the next connection then reads only what has arrived
 * since, and the rest of the mailbox is never read again. Keying this on the credential, which
 * disconnecting deletes, makes that impossible rather than something an adapter has to remember.
 *
 * A row whose credential is already gone describes a mailbox nothing holds, so it goes.
 */
alter table gmail_sync_state add column credential_id uuid;

update gmail_sync_state s
   set credential_id = c.id
  from source_credentials c
 where c.user_id = s.user_id
   and c.source = 'GMAIL';

delete from gmail_sync_state where credential_id is null;

alter table gmail_sync_state drop column user_id;

alter table gmail_sync_state alter column credential_id set not null;

alter table gmail_sync_state
    add constraint gmail_sync_state_pk primary key (credential_id);

alter table gmail_sync_state
    add constraint gmail_sync_state_credential_fk foreign key (credential_id)
        references source_credentials (id) on delete cascade;

/*
 * Where Gmail's own record of label changes is resumed from, which is how a message read in Gmail
 * becomes a read row here. One request for the whole mailbox, where comparing against every unread
 * message would cost a page for each hundred of them.
 */
alter table gmail_sync_state add column history_id bigint;

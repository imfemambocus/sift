/*
 * How many reads in a row reached nothing older than the floor.
 *
 * A read that fails records its reason on the credential, so the warning for that case already
 * exists. This counts the other one: every read succeeds and the walk back still gets nowhere,
 * which is what a mailbox that will not answer below its floor looks like from outside the log.
 */
alter table gmail_sync_state
    add column stalled_sweeps integer not null default 0;

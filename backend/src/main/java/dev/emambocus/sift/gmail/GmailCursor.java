package dev.emambocus.sift.gmail;

import java.time.Instant;

/**
 * How much of a mailbox Sift has read, as one unbroken run between two edges.
 *
 * <p>{@code newest} is how far forward the mailbox has been read and {@code oldest} how far back.
 * Two edges rather than one, because the search is the whole reason mail is in Sift and a search can
 * only find what was read: a mailbox is therefore read backwards to its beginning, while new mail
 * keeps arriving at the other end. {@code backfillDone} latches once nothing older is left.
 *
 * <p>{@code historyId} is a different question, and it is the mailbox's rather than Sift's: it is
 * where Gmail's own record of label changes is resumed from, so that a message read in Gmail becomes
 * a read row here. Null before the first sweep records one.
 */
record GmailCursor(Instant newest, Instant oldest, boolean backfillDone, Long historyId) {

	static GmailCursor empty() {
		return new GmailCursor(null, null, false, null);
	}

	/** False only before the first message of a mailbox has ever been read. */
	boolean started() {
		return newest != null;
	}

	/** Widens the run to hold one more arrival, in whichever direction it falls outside. */
	GmailCursor covering(Instant arrival) {
		Instant forward = newest == null || arrival.isAfter(newest) ? arrival : newest;
		Instant back = oldest == null || arrival.isBefore(oldest) ? arrival : oldest;
		return new GmailCursor(forward, back, backfillDone, historyId);
	}

	GmailCursor completed() {
		return new GmailCursor(newest, oldest, true, historyId);
	}

	GmailCursor floorAt(Instant floor) {
		return new GmailCursor(newest, floor, backfillDone, historyId);
	}

	GmailCursor resumingAt(Long history) {
		return new GmailCursor(newest, oldest, backfillDone, history);
	}
}

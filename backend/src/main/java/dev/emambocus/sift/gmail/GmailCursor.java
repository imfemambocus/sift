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
 *
 * <p>{@code stalledSweeps} counts the reads in a row that reached nothing older, which is the only
 * outward sign that the walk back has stopped getting anywhere while every read still succeeds.
 */
record GmailCursor(Instant newest, Instant oldest, boolean backfillDone, Long historyId, int stalledSweeps) {

	static GmailCursor empty() {
		return new GmailCursor(null, null, false, null, 0);
	}

	/** False only before the first message of a mailbox has ever been read. */
	boolean started() {
		return newest != null;
	}

	/** Widens the run to hold one more arrival, in whichever direction it falls outside. */
	GmailCursor covering(Instant arrival) {
		Instant forward = newest == null || arrival.isAfter(newest) ? arrival : newest;
		Instant back = oldest == null || arrival.isBefore(oldest) ? arrival : oldest;
		return new GmailCursor(forward, back, backfillDone, historyId, stalledSweeps);
	}

	GmailCursor completed() {
		return new GmailCursor(newest, oldest, true, historyId, 0);
	}

	GmailCursor floorAt(Instant floor) {
		return new GmailCursor(newest, floor, backfillDone, historyId, stalledSweeps);
	}

	GmailCursor resumingAt(Long history) {
		return new GmailCursor(newest, oldest, backfillDone, history, stalledSweeps);
	}

	/** This read reached older mail, so whatever held the walk back up is over. */
	GmailCursor progressing() {
		return new GmailCursor(newest, oldest, backfillDone, historyId, 0);
	}

	/** This read reached nothing older, and the count is what a page needs to be able to say so. */
	GmailCursor stalling() {
		return new GmailCursor(newest, oldest, backfillDone, historyId, stalledSweeps + 1);
	}
}

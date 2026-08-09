package dev.emambocus.sift.gmail;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the watermark, split from {@link GmailSource} for the reason every other
 * pair in this app is: the adapter makes network calls, and a {@code @Transactional} method it
 * called on itself would silently run with no transaction at all.
 */
@Service
class GmailSyncStore {

	private final GmailSyncStateRepository states;
	private final Clock clock;

	GmailSyncStore(GmailSyncStateRepository states, Clock clock) {
		this.states = states;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	Optional<GmailCursor> cursorFor(UUID credentialId) {
		return states.findByCredentialId(credentialId).map(state -> new GmailCursor(
				state.getNewestMessageAt(), state.getOldestMessageAt(), state.isBackfillDone(),
				state.getHistoryId()));
	}

	/**
	 * Widens the stored run and never narrows it: the forward edge only moves forward, the floor only
	 * moves back, the completed flag only latches on, and the history point only advances. Two sweeps
	 * that overlap therefore cannot make Sift forget a stretch of mailbox it has already read.
	 */
	@Transactional
	void advance(UUID credentialId, GmailCursor cursor) {
		if (!cursor.started()) {
			return;
		}

		GmailSyncState state = states.findByCredentialId(credentialId).orElseGet(() -> {
			GmailSyncState fresh = new GmailSyncState();
			fresh.setCredentialId(credentialId);
			return fresh;
		});

		if (isFurtherForward(cursor.newest(), state.getNewestMessageAt())) {
			state.setNewestMessageAt(cursor.newest());
		}
		if (isFurtherBack(cursor.oldest(), state.getOldestMessageAt())) {
			state.setOldestMessageAt(cursor.oldest());
		}
		if (cursor.backfillDone()) {
			state.setBackfillDone(true);
		}
		if (isLater(cursor.historyId(), state.getHistoryId())) {
			state.setHistoryId(cursor.historyId());
		}
		state.setUpdatedAt(clock.instant());
		states.save(state);
	}

	private static boolean isFurtherForward(Instant candidate, Instant stored) {
		return stored == null || candidate.isAfter(stored);
	}

	private static boolean isFurtherBack(Instant candidate, Instant stored) {
		return candidate != null && (stored == null || candidate.isBefore(stored));
	}

	private static boolean isLater(Long candidate, Long stored) {
		return candidate != null && (stored == null || candidate > stored);
	}
}

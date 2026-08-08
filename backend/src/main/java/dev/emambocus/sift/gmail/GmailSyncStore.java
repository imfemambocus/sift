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
	Optional<Instant> newestSeen(UUID userId) {
		return states.findByUserId(userId).map(GmailSyncState::getNewestMessageAt);
	}

	/**
	 * Moves the watermark forward, never back. A sweep that read nothing new must not rewind it, and
	 * two sweeps that overlap must not either.
	 */
	@Transactional
	void remember(UUID userId, Instant newestMessageAt) {
		GmailSyncState state = states.findByUserId(userId).orElseGet(() -> {
			GmailSyncState fresh = new GmailSyncState();
			fresh.setUserId(userId);
			return fresh;
		});

		if (state.getNewestMessageAt() != null && !newestMessageAt.isAfter(state.getNewestMessageAt())) {
			return;
		}
		state.setNewestMessageAt(newestMessageAt);
		state.setUpdatedAt(clock.instant());
		states.save(state);
	}
}

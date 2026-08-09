package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FeedSyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(FeedSyncScheduler.class);

	private final FeedSyncStore store;
	private final FeedSyncService syncService;

	FeedSyncScheduler(FeedSyncStore store, FeedSyncService syncService) {
		this.store = store;
		this.syncService = syncService;
	}

	/*
	 * one credential at a time, which is also what staggers the requests: a burst of parallel calls
	 * to the same instance is the one thing likeliest to get Sift rate limited.
	 */
	@Scheduled(fixedDelayString = "${sift.sync.interval}", initialDelayString = "${sift.sync.initial-delay}")
	public void sweep() {
		List<SourceCredential> due = store.dueForSync();
		if (due.isEmpty()) {
			return;
		}

		log.debug("syncing {} credential(s)", due.size());
		for (SourceCredential credential : due) {
			syncOne(credential);
		}
	}

	/*
	 * isolated per credential on purpose. one revoked token or one unreachable instance must never
	 * stop everyone else's sync, and the reason is already recorded on the credential by the service.
	 */
	private void syncOne(SourceCredential credential) {
		try {
			// empty when a read of this credential is already running, which needs no second one
			syncService.sync(credential).ifPresent(outcome -> log.debug(
					"{} sync for user {}: {} new, {} updated, {} resolved, {} fetched",
					credential.getSource(), credential.getUserId(),
					outcome.added(), outcome.updated(), outcome.resolved(), outcome.fetched()));
		}
		catch (RuntimeException ex) {
			log.warn("{} sync failed for user {}: {}",
					credential.getSource(), credential.getUserId(), ex.getMessage());
		}
	}
}

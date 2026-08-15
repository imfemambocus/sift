package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.SyncStatus;
import dev.emambocus.sift.sync.FeedSyncStore.SyncOutcome;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one credential's sync. Deliberately not transactional: the fetch is a network call
 * and must not be made while holding a database transaction open.
 */
@Service
public class FeedSyncService {

	private static final Logger log = LoggerFactory.getLogger(FeedSyncService.class);

	private final Map<SourceType, NotificationSource> sources;
	private final FeedSyncStore store;

	/*
	 * one read of a credential at a time. the sweep, "check now" and the read that follows an approval
	 * all reach the same credential, and two of them at once insert the same source_id twice, which
	 * violates the unique key and fails one of the two.
	 */
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	/*
	 * one thread, for the reason the sweep is serial: a burst of parallel calls to the same instance
	 * is the one thing likeliest to get Sift rate limited.
	 */
	private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "sift-source-read");
		thread.setDaemon(true);
		return thread;
	});

	FeedSyncService(List<NotificationSource> sources, FeedSyncStore store) {
		// every implementation on the classpath registers itself. adding a source needs no edit here
		this.sources = sources.stream()
				.collect(Collectors.toUnmodifiableMap(NotificationSource::id, Function.identity()));
		this.store = store;
	}

	@PreDestroy
	void stopBackgroundReads() {
		background.shutdownNow();
	}

	/** How much of this source is here, which only a source with a history of its own answers. */
	public SourceHistory history(SourceCredential credential) {
		NotificationSource source = sources.get(credential.getSource());
		return source == null ? SourceHistory.COMPLETE : source.history(credential);
	}

	/**
	 * Tells the source to read its history again from the beginning, and reads it now. False when the
	 * source has nothing to forget, which is not a failure: it already holds everything it can.
	 *
	 * <p>The rows are left where they are. They are keyed on the source's own id: the read that
	 * follows fills them in again rather than making a second copy, and the read state Sift holds
	 * survives it.
	 */
	public boolean rereadHistory(SourceCredential credential) {
		NotificationSource source = sources.get(credential.getSource());
		if (source == null || !source.rereadHistory(credential)) {
			return false;
		}
		syncInBackground(credential);
		return true;
	}

	/** Whether a read of this credential is running, which is what a page says as "syncing now". */
	public boolean isSyncing(UUID credentialId) {
		return inFlight.contains(credentialId);
	}

	/**
	 * Reads one credential now. Empty when a read of it is already running, which is an answer rather
	 * than a failure: the rows that read is about to store are the same ones a second read would fetch.
	 */
	public Optional<SyncOutcome> sync(SourceCredential credential) {
		if (!inFlight.add(credential.getId())) {
			log.debug("a {} read is already running for user {}", credential.getSource(), credential.getUserId());
			return Optional.empty();
		}
		try {
			return Optional.of(read(credential));
		}
		finally {
			inFlight.remove(credential.getId());
		}
	}

	/**
	 * Reads without holding the caller, for the approval that has just landed. A first read of a large
	 * mailbox is minutes of sequential requests, and nobody should watch a blank page for it.
	 *
	 * <p>The credential is claimed on the calling thread rather than inside the task. The source then
	 * reports itself as syncing from the moment this returns, not once a thread picks the task up.
	 */
	public void syncInBackground(SourceCredential credential) {
		UUID id = credential.getId();
		if (!inFlight.add(id)) {
			return;
		}
		try {
			background.execute(() -> {
				try {
					read(credential);
				}
				catch (RuntimeException ex) {
					// the reason is already on the credential, and there is no caller left to tell
					log.warn("the first {} read failed: {}", credential.getSource(), ex.getMessage());
				}
				finally {
					inFlight.remove(id);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			inFlight.remove(id);
			log.warn("could not start a {} read in the background: {}", credential.getSource(), ex.getMessage());
		}
	}

	private SyncOutcome read(SourceCredential credential) {
		NotificationSource source = sources.get(credential.getSource());
		if (source == null) {
			throw new IllegalStateException("no adapter registered for source " + credential.getSource());
		}

		try {
			// null means the stored value would not decrypt: the key changed under us. a reconnect,
			// not a retry, which is exactly what SourceAuthException records.
			if (credential.getAccessToken() == null) {
				throw new SourceAuthException(
						"Sift cannot read the stored token for this source. Reconnect it. "
								+ "This happens when sift.encryption-key changes.");
			}
			SourceFetch fetched = source.fetch(credential);
			SyncOutcome outcome = store.persist(credential.getUserId(), credential.getSource(), fetched.items());
			store.applyReadState(credential.getUserId(), credential.getSource(), fetched.readState());
			store.forget(credential.getUserId(), credential.getSource(), fetched.gone());
			/*
			 * last, and only once every row of this read is stored. a source that wrote down how far it
			 * got before that would step over the rows a failure here lost.
			 */
			fetched.commit().run();
			store.markSuccess(credential.getId());
			return outcome;
		}
		catch (SourceAuthException ex) {
			// terminal until the user reconnects: the sweep stops picking this credential up
			store.markFailure(credential.getId(), SyncStatus.AUTH_FAILED, ex.getMessage());
			throw ex;
		}
		catch (RuntimeException ex) {
			store.markFailure(credential.getId(), SyncStatus.ERROR, ex.getMessage());
			throw ex;
		}
	}
}

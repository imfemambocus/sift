package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.SyncStatus;
import dev.emambocus.sift.sync.FeedSyncStore.SyncOutcome;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one credential's sync. Deliberately not transactional: the fetch is a network call
 * and must not be made while holding a database transaction open.
 */
@Service
public class FeedSyncService {

	private final Map<SourceType, NotificationSource> sources;
	private final FeedSyncStore store;

	FeedSyncService(List<NotificationSource> sources, FeedSyncStore store) {
		// every implementation on the classpath registers itself, so adding a source needs no edit here
		this.sources = sources.stream()
				.collect(Collectors.toUnmodifiableMap(NotificationSource::id, Function.identity()));
		this.store = store;
	}

	/** Whether this source has read everything it holds, which only a source with a history answers. */
	public boolean historyComplete(SourceCredential credential) {
		NotificationSource source = sources.get(credential.getSource());
		return source == null || source.historyComplete(credential);
	}

	public SyncOutcome sync(SourceCredential credential) {
		NotificationSource source = sources.get(credential.getSource());
		if (source == null) {
			throw new IllegalStateException("no adapter registered for source " + credential.getSource());
		}

		try {
			// null means the stored value would not decrypt, so the key changed under us. it is a
			// reconnect, not a retry, which is exactly what SourceAuthException records.
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
			// terminal until the user reconnects, so the sweep stops picking this credential up
			store.markFailure(credential.getId(), SyncStatus.AUTH_FAILED, ex.getMessage());
			throw ex;
		}
		catch (RuntimeException ex) {
			store.markFailure(credential.getId(), SyncStatus.ERROR, ex.getMessage());
			throw ex;
		}
	}
}

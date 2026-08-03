package dev.emambocus.sift.sources;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.feed.FeedItemRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of source management, split from {@link SourceService} for the same reason
 * {@code FeedSyncStore} is split from the sync service: connecting involves network calls that must
 * not run inside a transaction, and a self-invoked {@code @Transactional} method would not open one.
 */
@Service
public class SourceCredentialStore {

	private final SourceCredentialRepository credentials;
	private final FeedItemRepository items;
	private final Clock clock;

	SourceCredentialStore(SourceCredentialRepository credentials, FeedItemRepository items, Clock clock) {
		this.credentials = credentials;
		this.items = items;
		this.clock = clock;
	}

	@Transactional
	public SourceCredential upsertPersonalAccessToken(UUID userId, SourceType source, String instanceUrl,
			String token) {
		return credentials.findByUserIdAndSource(userId, source)
				.map(existing -> {
					existing.replacePersonalAccessToken(instanceUrl, token);
					return credentials.save(existing);
				})
				.orElseGet(() -> credentials.save(
						SourceCredential.personalAccessToken(userId, source, instanceUrl, token, clock.instant())));
	}

	@Transactional(readOnly = true)
	public List<SourceCredential> forUser(UUID userId) {
		return credentials.findByUserId(userId);
	}

	@Transactional(readOnly = true)
	public Optional<SourceCredential> forUser(UUID userId, SourceType source) {
		return credentials.findByUserIdAndSource(userId, source);
	}

	@Transactional(readOnly = true)
	public long itemCount(UUID userId, SourceType source) {
		return items.countByUserIdAndSourceAndResolvedAtIsNull(userId, source);
	}

	/** Disconnecting takes the source's items with it, so reconnecting starts clean. */
	@Transactional
	public boolean disconnect(UUID userId, SourceType source) {
		Optional<SourceCredential> credential = credentials.findByUserIdAndSource(userId, source);
		if (credential.isEmpty()) {
			return false;
		}
		items.deleteByUserIdAndSource(userId, source);
		credentials.delete(credential.get());
		return true;
	}
}

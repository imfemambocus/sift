package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.SyncStatus;
import dev.emambocus.sift.feed.FeedItem;
import dev.emambocus.sift.feed.FeedItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database step of a sync, kept in its own bean.
 *
 * <p>Not merged into {@link FeedSyncService} because Spring's {@code @Transactional} works through a
 * proxy: one method of a bean calling another on {@code this} would silently run with no transaction
 * at all. Splitting the orchestration from the writes makes that impossible rather than subtle.
 */
@Service
public class FeedSyncStore {

	public record SyncOutcome(int added, int updated, int resolved, int fetched) {
	}

	private final FeedItemRepository items;
	private final SourceCredentialRepository credentials;
	private final Clock clock;

	FeedSyncStore(FeedItemRepository items, SourceCredentialRepository credentials, Clock clock) {
		this.items = items;
		this.credentials = credentials;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Optional<SourceCredential> credential(UUID credentialId) {
		return credentials.findById(credentialId);
	}

	@Transactional(readOnly = true)
	public List<SourceCredential> dueForSync() {
		return credentials.findByLastSyncStatusNot(SyncStatus.AUTH_FAILED);
	}

	@Transactional
	public SyncOutcome persist(UUID userId, SourceType source, List<IncomingItem> incoming) {
		Instant now = clock.instant();
		Map<String, FeedItem> existing = items.findByUserIdAndSource(userId, source).stream()
				.collect(Collectors.toMap(FeedItem::getSourceId, Function.identity(), (first, second) -> first));

		Set<String> seen = new HashSet<>();
		List<FeedItem> touched = new ArrayList<>();
		int added = 0;
		int updated = 0;

		for (IncomingItem item : incoming) {
			seen.add(item.sourceId());
			FeedItem stored = existing.get(item.sourceId());
			if (stored == null) {
				stored = new FeedItem();
				stored.setUserId(userId);
				stored.setSource(source);
				stored.setSourceId(item.sourceId());
				stored.setFirstSeenAt(now);
				added++;
			}
			else {
				updated++;
			}
			apply(stored, item, now);
			touched.add(stored);
		}

		int resolved = 0;
		for (FeedItem stored : existing.values()) {
			if (!seen.contains(stored.getSourceId()) && stored.getResolvedAt() == null) {
				stored.setResolvedAt(now);
				touched.add(stored);
				resolved++;
			}
		}

		items.saveAll(touched);
		return new SyncOutcome(added, updated, resolved, incoming.size());
	}

	@Transactional
	public void markSuccess(UUID credentialId) {
		credentials.findById(credentialId).ifPresent(credential -> {
			credential.recordSuccess(clock.instant());
			credentials.save(credential);
		});
	}

	@Transactional
	public void markFailure(UUID credentialId, SyncStatus status, String message) {
		credentials.findById(credentialId).ifPresent(credential -> {
			credential.recordFailure(clock.instant(), status, message);
			credentials.save(credential);
		});
	}

	private static void apply(FeedItem stored, IncomingItem item, Instant now) {
		stored.setKind(item.kind());
		stored.setPriority(item.priority());
		stored.setTitle(item.title());
		stored.setBody(item.body());
		stored.setActorName(item.actorName());
		stored.setActorAvatarUrl(item.actorAvatarUrl());
		stored.setContextLabel(item.contextLabel());
		stored.setContextUrl(item.contextUrl());
		stored.setUrl(item.url());
		stored.setSourceCreatedAt(item.sourceCreatedAt());
		stored.setRawPayload(item.rawPayload());
		stored.setLastSeenAt(now);
		// something that came back is live again, but notifiedAt is left alone so it is not
		// announced a second time
		stored.setResolvedAt(null);
	}
}

package dev.emambocus.sift.sync;

import dev.emambocus.sift.credential.SourceCredential;
import dev.emambocus.sift.credential.SourceCredentialRepository;
import dev.emambocus.sift.credential.SourceType;
import dev.emambocus.sift.credential.SyncStatus;
import dev.emambocus.sift.feed.FeedItem;
import dev.emambocus.sift.feed.FeedItemRepository;
import dev.emambocus.sift.feed.GroupKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

		/*
		 * an adapter must not hand over two items with the same id, and one reading several
		 * endpoints can do it by accident (a merge request that is both assigned to you and awaiting
		 * your review). left through, the second insert violates the unique key mid-flush and takes
		 * the whole sync down, so it is collapsed here rather than trusted not to happen.
		 */
		Map<String, IncomingItem> unique = new LinkedHashMap<>();
		for (IncomingItem item : incoming) {
			unique.putIfAbsent(item.sourceId(), item);
		}

		Map<String, FeedItem> existing = alreadyHeld(userId, source, unique.keySet());

		Set<String> seen = new HashSet<>();
		List<FeedItem> touched = new ArrayList<>();
		int added = 0;
		int updated = 0;

		for (IncomingItem item : unique.values()) {
			seen.add(item.sourceId());
			FeedItem stored = existing.get(item.sourceId());
			if (stored == null) {
				stored = new FeedItem();
				stored.setUserId(userId);
				stored.setSource(source);
				stored.setSourceId(item.sourceId());
				stored.setFirstSeenAt(now);
				/*
				 * only where the row is new. after that Sift's own read state owns it, so a message
				 * you read here does not come back unread because the mailbox still says so, and one
				 * you deliberately marked unread here is not overwritten either.
				 */
				if (item.alreadyRead()) {
					stored.setReadAt(now);
				}
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
			// an event is never resolved by absence; only state is
			if (!stored.isResolveWhenAbsent()) {
				continue;
			}
			if (!seen.contains(stored.getSourceId()) && stored.getResolvedAt() == null) {
				stored.setResolvedAt(now);
				/*
				 * resolved rows stay in the feed, so one that finished upstream before anyone opened it
				 * would sit there unread for ever and count towards the tab badge. it wants nothing from
				 * anybody, and finishing is what dealt with it. an existing read time is left alone.
				 */
				if (stored.getReadAt() == null) {
					stored.setReadAt(now);
				}
				touched.add(stored);
				resolved++;
			}
		}

		items.saveAll(touched);
		return new SyncOutcome(added, updated, resolved, unique.size());
	}

	/**
	 * Only the rows a sweep can act on: the ones it is about to write, and the ones its silence could
	 * resolve. Reading every row of the source instead would load a whole mailbox on every sweep to
	 * diff it against at most a page of incoming items.
	 *
	 * <p>Nothing is lost by narrowing it. A row is only ever resolved by absence, and only a row whose
	 * {@code resolveWhenAbsent} is true and which is not yet resolved can be.
	 */
	private Map<String, FeedItem> alreadyHeld(UUID userId, SourceType source, Set<String> sourceIds) {
		List<FeedItem> held = new ArrayList<>(items.findResolvable(userId, source));
		if (!sourceIds.isEmpty()) {
			held.addAll(items.findByUserIdAndSourceAndSourceIdIn(userId, source, sourceIds));
		}
		return held.stream()
				.collect(Collectors.toMap(FeedItem::getSourceId, Function.identity(), (first, second) -> first));
	}

	/**
	 * Drops rows for things the source no longer holds at all.
	 *
	 * <p>Not the same as resolving. A resolved row is finished work and stays in the feed as history;
	 * this is for a thing that has left the source, where there is nothing for the row to be about and
	 * its link would not open.
	 *
	 * @return how many rows went
	 */
	@Transactional
	public int forget(UUID userId, SourceType source, Set<String> sourceIds) {
		if (sourceIds.isEmpty()) {
			return 0;
		}
		return items.deleteBySourceId(userId, source, sourceIds);
	}

	/**
	 * Applies read state the source itself reports, which is the direction {@link SourceReadSync} does
	 * not cover: a message read in the mailbox rather than here.
	 *
	 * <p>Each statement touches only the rows that really change, so a row already read keeps the time
	 * it was read at and one already unread is not given a time it never had.
	 *
	 * @return how many rows changed
	 */
	@Transactional
	public int applyReadState(UUID userId, SourceType source, SourceReadState state) {
		if (state.isEmpty()) {
			return 0;
		}

		Instant now = clock.instant();
		int changed = readEverythingElse(userId, source, state, now);
		if (!state.unread().isEmpty()) {
			changed += items.markUnreadBySourceId(userId, source, state.unread());
		}
		return changed;
	}

	/*
	 * a source that can name every unread row it holds says so, and then the rest of that source has
	 * been read. it is the only way to recover after losing the point changes were being read from,
	 * because the changes themselves are gone.
	 */
	private int readEverythingElse(UUID userId, SourceType source, SourceReadState state, Instant now) {
		if (!state.unreadIsComplete()) {
			return state.read().isEmpty() ? 0 : items.markReadBySourceId(userId, source, state.read(), now);
		}
		if (state.unread().isEmpty()) {
			return items.markAllRead(userId, source, now);
		}
		return items.markReadExceptSourceId(userId, source, state.unread(), now);
	}

	/** Which account the credential turned out to belong to, learned on every sweep and written once. */
	@Transactional
	public void rememberAccount(UUID credentialId, String label) {
		if (label != null && !label.isBlank()) {
			credentials.recordAccount(credentialId, label);
		}
	}

	@Transactional
	public void markSuccess(UUID credentialId) {
		credentials.recordSyncOutcome(credentialId, clock.instant(), SyncStatus.OK, null);
	}

	@Transactional
	public void markFailure(UUID credentialId, SyncStatus status, String message) {
		credentials.recordSyncOutcome(credentialId, clock.instant(), status,
				SourceCredential.abbreviateError(message));
	}

	private static void apply(FeedItem stored, IncomingItem item, Instant now) {
		stored.setKind(item.kind());
		stored.setTitle(item.title());
		stored.setBody(item.body());
		stored.setActorName(item.actorName());
		stored.setActorAvatarUrl(item.actorAvatarUrl());
		stored.setContextLabel(item.contextLabel());
		stored.setContextUrl(item.contextUrl());
		stored.setUrl(item.url());
		stored.setAttachments(nameLines(item.attachments()));
		// recomputed rather than set once, since a source may correct the url of a row it already sent
		stored.setGroupKey(groupKeyOf(stored.getSource(), item));
		/*
		 * both columns are NOT NULL, and a source that omits a timestamp must degrade rather than
		 * fail the whole sync. one guard here beats one per adapter.
		 */
		Instant created = item.sourceCreatedAt() == null ? now : item.sourceCreatedAt();
		Instant activity = item.activityAt() == null ? created : item.activityAt();

		/*
		 * something that moved again after you read it is a new thing to look at, not one you have
		 * dealt with, so the read mark is dropped. only forward movement counts: a source that
		 * reports a slightly older timestamp on a later sweep must not un-read the row every pass.
		 */
		if (stored.getActivityAt() != null && activity.isAfter(stored.getActivityAt())) {
			stored.setReadAt(null);
		}

		stored.setSourceCreatedAt(created);
		stored.setActivityAt(activity);

		stored.setRawPayload(item.rawPayload());
		stored.setResolveWhenAbsent(item.resolveWhenAbsent());
		stored.setLastSeenAt(now);
		stored.setResolvedAt(null);
	}

	/*
	 * one name per line, and null where there are none, which is what the column's `is not null` test
	 * asks. a newline separates them because a file name may contain a space.
	 */
	private static String nameLines(List<String> names) {
		return names.isEmpty() ? null : String.join("\n", names);
	}

	private static String groupKeyOf(SourceType source, IncomingItem item) {
		if (item.conversationId() == null) {
			return GroupKeys.of(source, item.url());
		}
		return GroupKeys.ofConversation(source, item.conversationId());
	}
}

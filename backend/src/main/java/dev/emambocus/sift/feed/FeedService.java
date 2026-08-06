package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedService {

	private final FeedItemRepository items;
	private final Clock clock;

	FeedService(FeedItemRepository items, Clock clock) {
		this.items = items;
		this.clock = clock;
	}

	/**
	 * Everything the user has ever been sent, newest activity first.
	 *
	 * <p>Resolved rows are included. Read against unread is the only axis this list narrows on, so a
	 * to-do somebody completed and a merge request that was merged stay in it. {@code resolvedAt}
	 * still records that the source stopped reporting an item, and it is still what counts how many
	 * are waiting; it no longer decides what the list contains.
	 */
	@Transactional(readOnly = true)
	public List<FeedItemResponse> feed(UUID userId, SourceType source) {
		List<FeedItem> found = source == null
				? items.findByUserIdOrderByActivityAtDesc(userId)
				: items.findByUserIdAndSourceOrderByActivityAtDesc(userId, source);

		return found.stream().map(FeedItemResponse::of).toList();
	}

	/**
	 * Read is a per-item timestamp rather than a flag, so unread is "no timestamp" and a later sync
	 * can put an item back to unread by clearing it.
	 */
	@Transactional
	public void setRead(UUID userId, UUID itemId, boolean read) {
		int changed = items.updateReadAt(itemId, userId, read ? clock.instant() : null);
		if (changed == 0) {
			throw new FeedItemNotFoundException(itemId);
		}
	}

	/**
	 * Everything still unread, for one source or for all of them.
	 *
	 * <p>Scoped to the source rather than to whatever the list happens to be filtered to: the client's
	 * filter is a view, and "mark all read" that left rows behind because of one would be a worse
	 * surprise than one that clears the tab you are looking at.
	 *
	 * @return how many rows it touched, which is zero when there was nothing unread
	 */
	@Transactional
	public int markAllRead(UUID userId, SourceType source) {
		Instant now = clock.instant();
		return source == null ? items.markAllRead(userId, now) : items.markAllRead(userId, source, now);
	}
}

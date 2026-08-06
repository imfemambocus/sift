package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedService {

	private final FeedItemRepository items;
	private final FeedPageQuery pages;
	private final Clock clock;

	FeedService(FeedItemRepository items, FeedPageQuery pages, Clock clock) {
		this.items = items;
		this.pages = pages;
		this.clock = clock;
	}

	/**
	 * One page of everything the user has ever been sent.
	 *
	 * <p>Resolved rows are included. Read against unread is the only axis this list narrows on, so a
	 * to-do somebody completed and a merge request that was merged stay in it. {@code resolvedAt}
	 * still records that the source stopped reporting an item, and it is still what counts how many
	 * are waiting; it does not decide what the list contains.
	 *
	 * <p>The page is a number of groups, so one more group than the caller wants is asked for. If it
	 * came back there is a next page, and the last group kept is where it starts.
	 */
	@Transactional(readOnly = true)
	public FeedPageResponse page(FeedRequest request) {
		if (request.search().impossible()) {
			return FeedPageResponse.EMPTY;
		}
		List<List<FeedItem>> groups = intoGroups(pages.rows(request, request.limit() + 1));
		boolean more = groups.size() > request.limit();
		List<List<FeedItem>> shown = more ? groups.subList(0, request.limit()) : groups;

		List<FeedItemResponse> page = shown.stream()
				.flatMap(List::stream)
				.map(FeedItemResponse::of)
				.toList();

		return new FeedPageResponse(page, more ? cursorAfter(shown.getLast()) : null);
	}

	/**
	 * What each source's rows add up to, for everything that shows a number rather than a list.
	 *
	 * <p>Every source at once, because Home wants all of them, a source tab wants one, and the count
	 * on the tab wants the sum. One request answers all three.
	 */
	@Transactional(readOnly = true)
	public List<FeedSummaryResponse> summary(UUID userId) {
		Map<SourceType, Map<String, Long>> byKind = new EnumMap<>(SourceType.class);
		for (KindCount kind : items.countWaitingByKind(userId)) {
			byKind.computeIfAbsent(kind.source(), source -> new LinkedHashMap<>())
					.put(kind.kind(), kind.count());
		}

		return items.countBySource(userId).stream()
				.map(counts -> new FeedSummaryResponse(
						counts.source().slug(),
						counts.total(),
						counts.unread(),
						counts.waiting(),
						counts.waitingUnread(),
						byKind.getOrDefault(counts.source(), Map.of())))
				.toList();
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

	/*
	 * the query already returns a group's items together and in order, so one walk is enough. it must
	 * stay that way: grouping by a map instead would quietly reorder the page.
	 */
	private static List<List<FeedItem>> intoGroups(List<FeedItem> rows) {
		List<List<FeedItem>> groups = new ArrayList<>();
		String key = null;
		for (FeedItem row : rows) {
			if (!row.getGroupKey().equals(key)) {
				key = row.getGroupKey();
				groups.add(new ArrayList<>());
			}
			groups.getLast().add(row);
		}
		return groups;
	}

	/** A group ranks at its leading item, which is the first one the query returned for it. */
	private static String cursorAfter(List<FeedItem> group) {
		FeedItem lead = group.getFirst();
		return new FeedCursor(lead.getActivityAt(), lead.getGroupKey()).encode();
	}
}

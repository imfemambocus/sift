package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedService {

	private final FeedItemRepository items;

	FeedService(FeedItemRepository items) {
		this.items = items;
	}

	/** Resolved items are left out: the feed is what still wants attention, not a history. */
	@Transactional(readOnly = true)
	public List<FeedItemResponse> feed(UUID userId, SourceType source) {
		List<FeedItem> found = source == null
				? items.findByUserIdAndResolvedAtIsNullOrderBySourceCreatedAtDesc(userId)
				: items.findByUserIdAndSourceAndResolvedAtIsNullOrderBySourceCreatedAtDesc(userId, source);

		return found.stream().map(FeedItemResponse::of).toList();
	}
}

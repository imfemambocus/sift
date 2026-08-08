package dev.emambocus.sift.feed;

import java.time.Instant;
import java.util.UUID;

/**
 * Shaped for the list that renders it rather than for the table it came from: already normalised,
 * already narrowed, with the source's own vocabulary reduced to {@code kind}.
 */
public record FeedItemResponse(
		UUID id,
		String source,
		String kind,
		String title,
		String body,
		String actorName,
		String actorAvatarUrl,
		String contextLabel,
		String contextUrl,
		String url,
		/** What this row is about. See {@link GroupKeys}. Opaque: only equality means anything. */
		String groupKey,
		/** When the thing was created, for context. */
		Instant createdAt,
		/** When it last moved. What the list orders by and shows. */
		Instant activityAt,
		boolean read,
		/**
		 * The source has stopped reporting it: a to-do somebody completed, a merge request that was
		 * merged or closed. It stays in the feed, and this is what lets the row read as settled rather
		 * than as something still waiting.
		 */
		boolean resolved) {

	static FeedItemResponse of(FeedItem item) {
		return new FeedItemResponse(
				item.getId(),
				item.getSource().slug(),
				item.getKind(),
				item.getTitle(),
				item.getBody(),
				item.getActorName(),
				item.getActorAvatarUrl(),
				item.getContextLabel(),
				item.getContextUrl(),
				item.getUrl(),
				item.getGroupKey(),
				item.getSourceCreatedAt(),
				item.getActivityAt(),
				item.getReadAt() != null,
				item.getResolvedAt() != null);
	}
}

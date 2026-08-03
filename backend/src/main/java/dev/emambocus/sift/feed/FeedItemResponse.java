package dev.emambocus.sift.feed;

import java.time.Instant;
import java.util.UUID;

/**
 * Shaped for the list that renders it rather than for the table it came from: already normalised,
 * already prioritised, with the source's own vocabulary reduced to {@code kind}.
 */
public record FeedItemResponse(
		UUID id,
		String source,
		String kind,
		Priority priority,
		String title,
		String body,
		String actorName,
		String actorAvatarUrl,
		String contextLabel,
		String contextUrl,
		String url,
		/** When the thing was created, for context. */
		Instant createdAt,
		/** When it last moved. What the list orders by and shows. */
		Instant activityAt,
		boolean read) {

	static FeedItemResponse of(FeedItem item) {
		return new FeedItemResponse(
				item.getId(),
				item.getSource().slug(),
				item.getKind(),
				item.getPriority(),
				item.getTitle(),
				item.getBody(),
				item.getActorName(),
				item.getActorAvatarUrl(),
				item.getContextLabel(),
				item.getContextUrl(),
				item.getUrl(),
				item.getSourceCreatedAt(),
				item.getActivityAt(),
				item.getReadAt() != null);
	}
}

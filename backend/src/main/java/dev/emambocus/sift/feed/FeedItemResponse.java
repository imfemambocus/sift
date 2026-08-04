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
		/**
		 * What this row is about, so the several events one merge request produces collapse into one
		 * entry instead of repeating its title down the list. Opaque: only equality means anything.
		 */
		String groupKey,
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
				groupKey(item),
				item.getSourceCreatedAt(),
				item.getActivityAt(),
				item.getReadAt() != null);
	}

	/*
	 * the url without its fragment, which is what several rows about one thing have in common: a
	 * mention lands on #note_998, a reply on #note_1002, and the review request on the page itself.
	 * deciding this here rather than in the adapter keeps it one rule for every source; a source that
	 * needs its own answer (a mail thread id, say) is what would move it out.
	 */
	private static String groupKey(FeedItem item) {
		String url = item.getUrl();
		int fragment = url.indexOf('#');
		return item.getSource().slug() + ":" + (fragment < 0 ? url : url.substring(0, fragment));
	}
}

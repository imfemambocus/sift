package dev.emambocus.sift.feed;

import dev.emambocus.sift.credential.SourceType;
import java.util.UUID;

/**
 * One page of one person's feed, narrowed and ordered.
 *
 * @param source null is every source, which is what Home and the search ask for
 * @param cursor null is the first page
 * @param limit how many groups, never how many items. A merge request's four rows must not be split
 *     across a page boundary with half of them behind a button.
 */
public record FeedRequest(
		UUID userId,
		SourceType source,
		FeedFilter filter,
		FeedOrder order,
		FeedSearch search,
		FeedCursor cursor,
		int limit) {
}

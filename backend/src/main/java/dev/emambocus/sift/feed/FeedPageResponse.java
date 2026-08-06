package dev.emambocus.sift.feed;

import java.util.List;

/**
 * One page of the feed.
 *
 * @param items every item of the page's groups, groups in order and items in order inside each one,
 *     so the client builds its groups by walking the list once
 * @param nextCursor what to pass back for the next page, or null when this is the last one. It is
 *     null rather than a cursor that would answer nothing, so "Show more" is never a button that
 *     does nothing when pressed.
 */
public record FeedPageResponse(List<FeedItemResponse> items, String nextCursor) {

	static final FeedPageResponse EMPTY = new FeedPageResponse(List.of(), null);
}

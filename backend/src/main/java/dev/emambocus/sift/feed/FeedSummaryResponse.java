package dev.emambocus.sift.feed;

import java.util.Map;

/**
 * What one source's rows add up to, for the parts of the app that show a number rather than a list:
 * the All / Unread / Read counts, Home's card, and the count on the tab.
 *
 * <p>It exists because the feed is paged now. Each of those used to count the rows the browser was
 * holding, which only worked while the browser held all of them.
 */
public record FeedSummaryResponse(
		String source,
		long total,
		long unread,
		long waiting,
		long waitingUnread,
		Map<String, Long> waitingByKind) {
}

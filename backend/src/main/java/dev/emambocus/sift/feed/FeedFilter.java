package dev.emambocus.sift.feed;

/**
 * Read against unread, which is the only axis the feed narrows on. All is one of the three, never
 * "neither of the other two selected".
 */
public enum FeedFilter {

	ALL, UNREAD, READ;

	/** Absent is All, which is what a client that says nothing about it wants. */
	public static FeedFilter parse(String value) {
		if (value == null || value.isBlank()) {
			return ALL;
		}
		for (FeedFilter filter : values()) {
			if (filter.name().equalsIgnoreCase(value.trim())) {
				return filter;
			}
		}
		throw new InvalidFeedRequestException("Unknown filter '" + value + "'. Use all, unread or read.");
	}
}

import type { FeedItem } from "./feed";

/** All is one of the three, never "neither of the other two selected". */
export type FeedFilter = "all" | "unread" | "read";

/**
 * Both orders are on `activityAt`, which is deliberate rather than a short list of options.
 *
 * <p>`FeedList` groups by day and only merges *consecutive* rows carrying the same label, so a sort on
 * anything other than the field the labels come from repeats "Today" down the page. Two orders that are
 * right beat four that look broken; a sort on another field has to teach the day grouping about it
 * first.
 *
 * <p>Oldest first is not a curiosity: on a list of things waiting on you, the one that has been waiting
 * longest is the one you are most likely neglecting.
 */
export type FeedOrder = "latest" | "waiting";

export const ORDER_LABEL: Record<FeedOrder, string> = {
	latest: "Latest activity",
	waiting: "Longest waiting",
};

export function applyView(
	items: readonly FeedItem[],
	filter: FeedFilter,
	order: FeedOrder,
): readonly FeedItem[] {
	const filtered = items.filter((item) => matches(item, filter));
	if (order === "latest") {
		// the API already answers newest first, so this is the order it arrived in
		return filtered;
	}
	/*
	 * by parsed timestamp, not by string. these are ISO-8601 and mostly sort lexicographically, but
	 * "...:00Z" against "...:00.5Z" does not: '.' sorts before 'Z', so half a second would come first.
	 */
	return [...filtered].sort((one, two) => Date.parse(one.activityAt) - Date.parse(two.activityAt));
}

function matches(item: FeedItem, filter: FeedFilter): boolean {
	if (filter === "unread") {
		return !item.read;
	}
	if (filter === "read") {
		return item.read;
	}
	return true;
}

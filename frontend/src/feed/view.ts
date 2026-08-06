/*
 * Both of these are query parameters now rather than something applied to a list in the browser: the
 * server narrows and orders the whole history, and the client only ever holds the pages it asked for.
 * The wording of the values matches what the API accepts.
 */

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

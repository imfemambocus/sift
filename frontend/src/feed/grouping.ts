import type { FeedItem } from "./feed";

/**
 * Several events on one merge request, collected under the thing they are all about. A review
 * request, a reply, a push and a merge each earn their own row from the backend, and without this
 * the list repeats the same title four times over.
 */
export type FeedGroup = {
	readonly key: string;
	/** The most recent of them, which is where the group sits in the list and what it is titled by. */
	readonly newest: FeedItem;
	readonly items: readonly FeedItem[];
};

type Building = { readonly key: string; readonly newest: FeedItem; readonly items: FeedItem[] };

/** Relies on the API's order, newest activity first, and keeps it for the groups and within them. */
export function intoGroups(items: readonly FeedItem[]): FeedGroup[] {
	const groups: Building[] = [];
	const byKey = new Map<string, Building>();

	for (const item of items) {
		const existing = byKey.get(item.groupKey);
		if (existing === undefined) {
			const group: Building = { key: item.groupKey, newest: item, items: [item] };
			groups.push(group);
			byKey.set(item.groupKey, group);
		}
		else {
			existing.items.push(item);
		}
	}
	return groups;
}

export function groupUnread(group: FeedGroup): number {
	return group.items.filter((item) => !item.read).length;
}

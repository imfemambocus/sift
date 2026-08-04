import type { FeedItem } from "./feed";

/**
 * Several events on one merge request, collected under the thing they are all about. A review
 * request, a reply, a push and a merge each earn their own row from the backend, and without this
 * the list repeats the same title four times over.
 */
export type FeedGroup = {
	readonly key: string;
	/**
	 * Whichever of them comes first in the order the list is in, so it is what the group is titled and
	 * dated by and where it sits. The newest under "latest activity", the oldest under "longest
	 * waiting", which is the right answer in both cases.
	 */
	readonly lead: FeedItem;
	readonly items: readonly FeedItem[];
};

type Building = { readonly key: string; readonly lead: FeedItem; readonly items: FeedItem[] };

/** Keeps the order it is handed, both between groups and inside them. */
export function intoGroups(items: readonly FeedItem[]): FeedGroup[] {
	const groups: Building[] = [];
	const byKey = new Map<string, Building>();

	for (const item of items) {
		const existing = byKey.get(item.groupKey);
		if (existing === undefined) {
			const group: Building = { key: item.groupKey, lead: item, items: [item] };
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

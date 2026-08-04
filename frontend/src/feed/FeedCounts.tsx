import type { FeedItem } from "./feed";
import { unreadCount } from "./feed";

/*
 * replaces the event-family legend. once every row spells its own type out in words, a legend is a
 * colour chart; what a list this size actually wants to say is how much of it you have not looked at.
 */
export function FeedCounts({ items }: { readonly items: readonly FeedItem[] }) {
	if (items.length === 0) {
		return null;
	}

	const unread = unreadCount(items);
	if (unread === 0) {
		return <p className="text-[12px] text-fg-muted">Nothing unread</p>;
	}

	return (
		<p className="flex items-baseline gap-2 text-[12px] text-fg-muted">
			{/* brass, the same thing the unread edge on a row means */}
			<span className="text-accent">{unread} unread</span>
			<span aria-hidden className="text-fg-muted/45">
				&middot;
			</span>
			<span>{items.length - unread} read</span>
		</p>
	);
}

import { useEffect } from "react";
import { totalUnread, useFeedSummary } from "./feed";

const TITLE = "Sift";
const ICON = "/favicon.svg";
const ICON_UNREAD = "/favicon-unread.svg";

/**
 * The tab is the notification: a count in its title and a badge on its favicon. Mounted once in the
 * app frame, so it is every source rather than whichever tab is open.
 *
 * <p>It reads the summary rather than counting the rows on screen, because the feed is paged and
 * a page cannot count what it does not hold.
 *
 * <p>Honest limit, the same one browser Notifications will have: it only says anything while a tab is
 * open. It also cannot be seen at all when the tab is the active one, which is fine, since then the
 * feed itself is on screen.
 */
export function useUnreadBadge() {
	const { data } = useFeedSummary();
	const unread = totalUnread(data);

	useEffect(() => {
		apply(unread);
	}, [unread]);

	// signing out unmounts the frame, and the tab must not go on claiming a count after that
	useEffect(() => () => apply(0), []);
}

function apply(unread: number) {
	/*
	 * the count goes after the name because a tab strip truncates from the right: the name stays
	 * legible and the count is what goes first. the favicon carries the same signal, so a tab too
	 * narrow for the count still shows the brass disc.
	 */
	document.title = unread > 0 ? `${TITLE} (${unread})` : TITLE;

	const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
	if (link !== null) {
		// two files rather than a canvas: swapping the href is one line and needs no drawing code
		link.href = unread > 0 ? ICON_UNREAD : ICON;
	}
}

import { useEffect } from "react";
import { unreadCount, useFeed } from "./feed";

const TITLE = "Sift";
const ICON = "/favicon.svg";
const ICON_UNREAD = "/favicon-unread.svg";

/**
 * The tab is the notification, until real Notifications land: a count in its title and a badge on its
 * favicon, from the feed the app already polls. Mounted once in the app frame, so it is the whole
 * working set rather than whichever source's tab is open.
 *
 * <p>Honest limit, the same one browser Notifications will have: it only says anything while a tab is
 * open. It also cannot be seen at all when the tab is the active one, which is fine, since then the
 * feed itself is on screen.
 */
export function useUnreadBadge() {
	const { data } = useFeed();
	const unread = data === undefined ? 0 : unreadCount(data);

	useEffect(() => {
		apply(unread);
	}, [unread]);

	// signing out unmounts the frame, and the tab must not go on claiming a count after that
	useEffect(() => () => apply(0), []);
}

function apply(unread: number) {
	document.title = unread > 0 ? `(${unread}) ${TITLE}` : TITLE;

	const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
	if (link !== null) {
		// two files rather than a canvas: swapping the href is one line and needs no drawing code
		link.href = unread > 0 ? ICON_UNREAD : ICON;
	}
}

import { useEffect, useState } from "react";
import { Outlet, useLocation } from "react-router";
import { useUnreadBadge } from "../feed/unreadBadge";
import { useUnreadSound } from "../feed/unreadSound";
import { isSearching } from "../search/search";
import { SearchField } from "../search/SearchField";
import { SearchResults } from "../search/SearchResults";
import { useRefreshWhenSynced } from "../sources/sources";
import { SidebarRail } from "./SidebarRail";

/*
 * blacklisted rather than whitelisted: a second source's tab gets the field without anyone
 * remembering to add it. settings is the exception because there is no feed behind it, and a field
 * that replaces the page with feed results has nothing to offer someone pasting a token in.
 */
const WITHOUT_SEARCH: readonly string[] = ["/settings"];

export function AppLayout() {
	/*
	 * the query lives here rather than in a context because this is the only place that needs it. the
	 * field is in the frame and the results replace the page; no route knows anything about it.
	 */
	const [query, setQuery] = useState("");
	const { pathname } = useLocation();
	useUnreadBadge();
	useUnreadSound();
	// the frame, because a read that finishes has to reach whichever page is open
	useRefreshWhenSynced();

	const searchable = !WITHOUT_SEARCH.includes(pathname);

	/*
	 * clicking a section is asking for that section: it hands the search back. this layout does not
	 * unmount on a route change, and without an effect the query would follow you around the app.
	 */
	useEffect(() => {
		setQuery("");
	}, [pathname]);

	return (
		<div className="flex min-h-dvh">
			<SidebarRail />
			{/*
			  * on a narrow screen the rail is a bar along the bottom and leaves the flow. the room it
			  * takes is put back here, once, rather than by every page
			  */}
			<div className="min-w-0 flex-1 pb-14 sm:pb-0">
				{/* sticky, because the point of it is being the one place you go from anywhere */}
				{searchable ? (
					<div className="sticky top-0 z-10 bg-bg">
						<div className="mx-auto w-full max-w-3xl px-4 pt-6 pb-3 sm:px-8">
							<SearchField value={query} onChange={setQuery} />
						</div>
					</div>
				) : (
					// `Page` carries less top padding than the frame above it needs. a page without the
					// field puts the difference back rather than starting up against the edge
					<div className="pt-6" />
				)}

				{/*
				  * `searchable` again, not just the query. the effect above clears it after the render
				  * that navigated, and for one frame results would otherwise paint over settings.
				  */}
				{searchable && isSearching(query) ? <SearchResults query={query} /> : <Outlet />}
			</div>
		</div>
	);
}

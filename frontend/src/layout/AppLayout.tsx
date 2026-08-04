import { useEffect, useState } from "react";
import { Outlet, useLocation } from "react-router";
import { useUnreadBadge } from "../feed/unreadBadge";
import { isSearching } from "../search/search";
import { SearchField } from "../search/SearchField";
import { SearchResults } from "../search/SearchResults";
import { SidebarRail } from "./SidebarRail";

/*
 * blacklisted rather than whitelisted, so a second source's tab gets the field without anyone
 * remembering to add it. settings is the exception because there is no feed behind it: a field that
 * replaces the page with feed results has nothing to offer someone pasting a token in.
 */
const WITHOUT_SEARCH: readonly string[] = ["/settings"];

export function AppLayout() {
	/*
	 * the query lives here rather than in a context because this is the only place that needs it: the
	 * field is in the frame and the results replace the page, so no route knows anything about it.
	 */
	const [query, setQuery] = useState("");
	const { pathname } = useLocation();
	useUnreadBadge();

	const searchable = !WITHOUT_SEARCH.includes(pathname);

	/*
	 * clicking a section is asking for that section, so it hands the search back. this layout does not
	 * unmount on a route change, so without it the query would follow you around the app.
	 */
	useEffect(() => {
		setQuery("");
	}, [pathname]);

	return (
		<div className="flex min-h-dvh">
			<SidebarRail />
			<div className="min-w-0 flex-1">
				{/* sticky, because the point of it is being the one place you go from anywhere */}
				{searchable ? (
					<div className="sticky top-0 z-10 bg-bg">
						<div className="mx-auto w-full max-w-3xl px-8 pt-6 pb-3">
							<SearchField value={query} onChange={setQuery} />
						</div>
					</div>
				) : (
					// `Page` carries less top padding now that the frame usually sits above it, so a page
					// without the field puts the difference back rather than starting up against the edge
					<div className="pt-6" />
				)}

				{/*
				  * `searchable` again, not just the query: the effect above clears it after the render
				  * that navigated, so for one frame results would otherwise paint over settings.
				  */}
				{searchable && isSearching(query) ? <SearchResults query={query} /> : <Outlet />}
			</div>
		</div>
	);
}

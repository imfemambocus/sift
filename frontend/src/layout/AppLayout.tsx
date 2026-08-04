import { useState } from "react";
import { Outlet } from "react-router";
import { useUnreadBadge } from "../feed/unreadBadge";
import { isSearching } from "../search/search";
import { SearchField } from "../search/SearchField";
import { SearchResults } from "../search/SearchResults";
import { SidebarRail } from "./SidebarRail";

export function AppLayout() {
	/*
	 * the query lives here rather than in a context because this is the only place that needs it: the
	 * field is in the frame and the results replace the page, so no route knows anything about it.
	 * plain state, so navigating away drops the search, which is what "hands the page back" means.
	 */
	const [query, setQuery] = useState("");
	useUnreadBadge();

	return (
		<div className="flex min-h-dvh">
			<SidebarRail />
			<div className="min-w-0 flex-1">
				{/* sticky, because the point of it is being the one place you go from anywhere */}
				<div className="sticky top-0 z-10 bg-bg">
					<div className="mx-auto w-full max-w-3xl px-8 pt-6 pb-3">
						<SearchField value={query} onChange={setQuery} />
					</div>
				</div>

				{isSearching(query) ? <SearchResults query={query} /> : <Outlet />}
			</div>
		</div>
	);
}

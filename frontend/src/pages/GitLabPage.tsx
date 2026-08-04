import { useState } from "react";
import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { bySource, unreadCount, useFeed, useMarkAllRead } from "../feed/feed";
import { FeedFilters } from "../feed/FeedFilters";
import { FeedList } from "../feed/FeedList";
import { FeedOrderToggle } from "../feed/FeedOrderToggle";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import type { FeedFilter, FeedOrder } from "../feed/view";
import { applyView } from "../feed/view";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { LastSynced } from "../sources/LastSynced";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useIsSyncing, useSource } from "../sources/sources";

export function GitLabPage() {
	const { data: feed, isPending } = useFeed();
	const { data: source } = useSource("gitlab");
	const [filter, setFilter] = useState<FeedFilter>("all");
	const [order, setOrder] = useState<FeedOrder>("latest");
	const markAllRead = useMarkAllRead("gitlab");

	// narrowed here rather than by a second request; see useFeed
	const items = bySource(feed ?? [], "gitlab");
	const shown = applyView(items, filter, order);

	/*
	 * a refresh someone pressed skeletons the list, so the wait is visible where the new rows will
	 * be. the background sweep never does: replacing the list every thirty seconds unasked would be
	 * a flicker rather than an answer. called on its own line, since `||` would short-circuit a hook.
	 */
	const syncing = useIsSyncing("gitlab");
	const loading = useMinimumDuration(isPending || syncing);
	const unread = unreadCount(items);

	return (
		<Page title="GitLab" description="To-dos, review requests and mentions from your GitLab instance.">
			<SourceAlerts only="gitlab" />

			{/* reachable from where the feed is, not buried in settings */}
			<div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
				<FeedFilters items={items} filter={filter} onChange={setFilter} />

				<div className="flex flex-wrap items-center gap-2">
					{items.length > 0 && <FeedOrderToggle order={order} onChange={setOrder} />}
					{/*
					  * bordered where the sort toggle is not, because they are different kinds of thing
					  * sitting next to each other: one changes what you see, this one changes the data.
					  * `Button` is not used here, since its h-10 would tower over a row of 12px controls.
					  */}
					{unread > 0 && (
						<button
							type="button"
							onClick={() => markAllRead.mutate()}
							disabled={markAllRead.isPending}
							className="rounded-control border border-border px-2 py-1 text-[12px] text-fg transition-colors hover:bg-raised disabled:cursor-not-allowed disabled:opacity-55"
						>
							Mark all read
						</button>
					)}
					{source !== undefined && <LastSynced source={source} />}
				</div>
			</div>

			{loading ? <FeedSkeleton /> : <FeedList items={shown} empty={emptyFor(filter, source !== undefined)} />}
		</Page>
	);
}

/*
 * a filter that hides everything is not the same as nothing being there, and saying "your to-do list is
 * empty" when it is not would be the one lie this app must never tell.
 */
function emptyFor(filter: FeedFilter, connected: boolean) {
	if (!connected) {
		return <NotConnected />;
	}
	if (filter === "unread") {
		return <EmptyState title="Nothing unread" description="Everything GitLab is showing you has been looked at. Switch to All to see it again." />;
	}
	if (filter === "read") {
		return <EmptyState title="Nothing read yet" description="Open a row, or use the tick beside it, and it will appear here." />;
	}
	return <AllClear />;
}

function NotConnected() {
	return (
		<EmptyState
			title="GitLab is not connected"
			description="Sift reads your GitLab to-do list, which is already scoped to you, rather than everything happening in your projects."
			action={
				<Link
					to="/settings"
					className="text-[13px] text-fg underline decoration-border underline-offset-4 hover:decoration-accent"
				>
					Connect it in settings
				</Link>
			}
		/>
	);
}

function AllClear() {
	return (
		<EmptyState
			title="Your GitLab to-do list is empty"
			description="Nothing there is waiting on you. Anything new shows up here within a few minutes."
		/>
	);
}

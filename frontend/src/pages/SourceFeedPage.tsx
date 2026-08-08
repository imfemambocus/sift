import type { ReactNode } from "react";
import { useState } from "react";
import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { itemsOf, summaryFor, useFeedPages, useFeedSummary, useMarkAllRead } from "../feed/feed";
import { FeedFilters } from "../feed/FeedFilters";
import { FeedList } from "../feed/FeedList";
import { FeedOrderToggle } from "../feed/FeedOrderToggle";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import type { FeedFilter, FeedOrder } from "../feed/view";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { sourceName } from "../sources/labels";
import { LastSynced } from "../sources/LastSynced";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useIsSyncing, useSource } from "../sources/sources";

type SourceFeedPageProps = {
	readonly source: string;
	readonly description: string;
	/** What to say when the source is connected, the filter is All, and there is genuinely nothing. */
	readonly allClear: ReactNode;
	/** What this source is for, shown when it is not connected yet. */
	readonly offer: string;
};

/**
 * One source's feed. Every source's tab is this page: the filters, the two orders, mark-all-read,
 * the skeleton rules and the paging are identical, and only the wording is not.
 *
 * <p>One copy rather than one per source. Three sentences of wording is the whole difference, and a
 * second copy of a hundred lines is how two tabs come to disagree about when a skeleton shows.
 */
export function SourceFeedPage({ source: slug, description, allClear, offer }: SourceFeedPageProps) {
	const [filter, setFilter] = useState<FeedFilter>("all");
	const [order, setOrder] = useState<FeedOrder>("latest");
	const feed = useFeedPages({ source: slug, filter, order });
	const { data: summary } = useFeedSummary();
	const { data: source } = useSource(slug);
	const markAllRead = useMarkAllRead(slug);

	// narrowed, ordered and paged by the server; this is only the pages that have been asked for
	const shown = itemsOf(feed.data);
	const counts = summaryFor(summary, slug);

	/*
	 * a refresh someone pressed skeletons the list, so the wait is visible where the new rows will
	 * be. the background sweep never does: replacing the list every thirty seconds unasked would be
	 * a flicker rather than an answer. called on its own line, since `||` would short-circuit a hook.
	 */
	const syncing = useIsSyncing(slug);
	const loading = useMinimumDuration(feed.isPending || syncing);

	return (
		<Page title={sourceName(slug)} description={description}>
			<SourceAlerts only={slug} />

			{/* reachable from where the feed is, not buried in settings */}
			<div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
				<FeedFilters counts={counts} filter={filter} onChange={setFilter} />

				<div className="flex flex-wrap items-center gap-2">
					{counts.total > 0 && <FeedOrderToggle order={order} onChange={setOrder} />}
					{/*
					  * bordered where the sort toggle is not, because they are different kinds of thing
					  * sitting next to each other: one changes what you see, this one changes the data.
					  * `Button` is not used here, since its h-10 would tower over a row of 12px controls.
					  */}
					{counts.unread > 0 && (
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

			{loading ? <FeedSkeleton /> : (
				<FeedList
					items={shown}
					empty={emptyFor(slug, filter, source !== undefined, allClear, offer)}
					hasMore={feed.hasNextPage}
					onMore={() => {
						// it never rejects: a failure lands in the query's own error state
						feed.fetchNextPage();
					}}
					loadingMore={feed.isFetchingNextPage}
				/>
			)}
		</Page>
	);
}

/*
 * a filter that hides everything is not the same as nothing being there, and saying "there is
 * nothing" under an active unread filter would be the one lie this app must never tell.
 */
function emptyFor(slug: string, filter: FeedFilter, connected: boolean, allClear: ReactNode, offer: string) {
	if (!connected) {
		return <NotConnected slug={slug} offer={offer} />;
	}
	if (filter === "unread") {
		return (
			<EmptyState
				title="Nothing unread"
				description="Everything here has been looked at. Switch to All to see it again."
			/>
		);
	}
	if (filter === "read") {
		return (
			<EmptyState
				title="Nothing read yet"
				description="Open a row, or use the tick beside it, and it will appear here."
			/>
		);
	}
	return allClear;
}

function NotConnected({ slug, offer }: { readonly slug: string; readonly offer: string }) {
	return (
		<EmptyState
			title={`${sourceName(slug)} is not connected`}
			description={offer}
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

import { EmptyState } from "../components/EmptyState";
import { itemsOf, useFeedPages } from "../feed/feed";
import { FeedList } from "../feed/FeedList";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { Page } from "../layout/Page";
import { useDebounced } from "../lib/useDebounced";
import { useMinimumDuration } from "../lib/minimumDuration";
import { useIsSyncing } from "../sources/sources";

/** Long enough that a word is typed before it is asked for, short enough not to feel held back. */
const SETTLE = 250;

/** The whole history, not the tab you happen to be on: one place to find a thing you half remember. */
export function SearchResults({ query }: { readonly query: string }) {
	/*
	 * the matching moved to the database with the paging, so this is a request rather than a pass
	 * over a cached array. it waits for the typing to stop, and stays off until it does, so a single
	 * letter never asks for the unnarrowed feed.
	 */
	const settled = useDebounced(query.trim(), SETTLE);
	const ready = settled !== "";
	const feed = useFeedPages({ filter: "all", order: "latest", query: settled }, ready);

	const syncing = useIsSyncing();
	const loading = useMinimumDuration(!ready || feed.isPending || syncing);

	return (
		<Page title="Search" description="Across every connected source, most recent activity first.">
			{loading ? <FeedSkeleton /> : (
				<FeedList
					items={itemsOf(feed.data)}
					empty={<Nothing query={settled} />}
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

function Nothing({ query }: { readonly query: string }) {
	return (
		<EmptyState
			title={`Nothing matches "${query}"`}
			description="Typos are forgiven and words can be in any order, so this is most likely a word that is not there at all. is:unread, is:mr, is:issue, project: and from: narrow rather than widen. Escape clears the field."
		/>
	);
}

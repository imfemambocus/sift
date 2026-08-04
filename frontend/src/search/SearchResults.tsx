import { useMemo } from "react";
import { EmptyState } from "../components/EmptyState";
import { useFeed } from "../feed/feed";
import { FeedList } from "../feed/FeedList";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { useIsSyncing } from "../sources/sources";
import { searchFeed } from "./search";

/** The whole working set, not the tab you happen to be on: one place to find a thing you half remember. */
export function SearchResults({ query }: { readonly query: string }) {
	const { data: items, isPending } = useFeed();
	// on `items`, never on a `?? []` fallback: a fresh array every render would defeat the memo
	const matches = useMemo(() => searchFeed(items ?? [], query), [items, query]);
	const total = items?.length ?? 0;
	const syncing = useIsSyncing();
	const loading = useMinimumDuration(isPending || syncing);

	return (
		<Page title="Search" description={`${matches.length} of ${total} across every connected source`}>
			{loading ? <FeedSkeleton /> : <FeedList items={matches} empty={<Nothing query={query} />} />}
		</Page>
	);
}

function Nothing({ query }: { readonly query: string }) {
	return (
		<EmptyState
			title={`Nothing matches "${query.trim()}"`}
			description="Typos are forgiven and words can be in any order, so this is most likely a word that is not there at all. is:unread, is:mr, is:issue, project: and from: narrow rather than widen. Escape clears the field."
		/>
	);
}

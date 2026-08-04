import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { FeedCounts } from "../feed/FeedCounts";
import { bySource, useFeed } from "../feed/feed";
import { FeedList } from "../feed/FeedList";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { LastSynced } from "../sources/LastSynced";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useSource } from "../sources/sources";

export function GitLabPage() {
	const { data: feed, isPending } = useFeed();
	const { data: source } = useSource("gitlab");
	// narrowed here rather than by a second request; see useFeed
	const items = bySource(feed ?? [], "gitlab");
	const loading = useMinimumDuration(isPending);
	const empty = source === undefined ? <NotConnected /> : <AllClear />;

	return (
		<Page title="GitLab" description="To-dos, review requests and mentions from your GitLab instance.">
			<SourceAlerts only="gitlab" />
			{/* reachable from where the feed is, not buried in settings */}
			<div className="flex flex-wrap items-center justify-between gap-3">
				<FeedCounts items={items} />
				{source !== undefined && <LastSynced source={source} />}
			</div>
			{loading ? <FeedSkeleton /> : <FeedList items={items} empty={empty} />}
		</Page>
	);
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

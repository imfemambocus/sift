import type { ReactNode } from "react";
import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { summaryFor, useFeedSummary } from "../feed/feed";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { SourceSummary } from "../home/SourceSummary";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useIsSyncing, useSources } from "../sources/sources";

export function HomePage() {
	// counts, not rows: a card says how much is new, and the rows are a page away
	const { data: summary, isPending } = useFeedSummary();
	const { data: sources } = useSources();
	// any source, since a card here summarises each of them
	const syncing = useIsSyncing();
	const loading = useMinimumDuration(isPending || sources === undefined || syncing);

	let body: ReactNode;
	// the undefined check is repeated rather than left to the hook, which cannot narrow the type
	if (loading || sources === undefined) {
		body = <FeedSkeleton />;
	}
	else if (sources.length === 0) {
		body = <NothingConnected />;
	}
	else {
		body = (
			<div className="grid gap-4 sm:grid-cols-2">
				{sources.map((source) => (
					<SourceSummary key={source.source} source={source} counts={summaryFor(summary, source.source)} />
				))}
			</div>
		);
	}

	return (
		<Page title="Home" description="What is new, and where.">
			<SourceAlerts />
			{body}
		</Page>
	);
}

function NothingConnected() {
	return (
		<EmptyState
			title="Nothing connected yet"
			description="Connect GitLab and the things that name you, assign you, or ask for your review will land here."
			action={
				<Link
					to="/settings"
					className="text-[13px] text-fg underline decoration-border underline-offset-4 hover:decoration-accent"
				>
					Connect GitLab
				</Link>
			}
		/>
	);
}

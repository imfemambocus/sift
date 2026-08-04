import type { ReactNode } from "react";
import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { bySource, useFeed } from "../feed/feed";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { SourceSummary } from "../home/SourceSummary";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useSources } from "../sources/sources";

export function HomePage() {
	const { data: items, isPending } = useFeed();
	const { data: sources } = useSources();
	const loading = useMinimumDuration(isPending || sources === undefined);

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
					<SourceSummary key={source.source} source={source} items={bySource(items ?? [], source.source)} />
				))}
			</div>
		);
	}

	return (
		<Page title="Home" description="How much is waiting, and where.">
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

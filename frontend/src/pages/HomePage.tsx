import type { ReactNode } from "react";
import { summaryFor, useFeedSummary } from "../feed/feed";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { ConnectCard } from "../home/ConnectCard";
import { SourceSummary } from "../home/SourceSummary";
import { Page } from "../layout/Page";
import { useMinimumDuration } from "../lib/minimumDuration";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useConnectors, useSources } from "../sources/sources";

export function HomePage() {
	// counts, not rows: a card says how much is new, and the rows are a page away
	const { data: summary, isPending } = useFeedSummary();
	const { data: sources } = useSources();
	// and what could be connected, so a source nobody has yet is offered rather than hidden
	const { data: connectors } = useConnectors();
	/*
	 * only the first load, never a refresh somebody pressed. a card already says "Syncing now" and
	 * turns its own icon, so replacing the whole page with a skeleton would hide the one card that
	 * was asked about, along with every other card and the offers.
	 */
	const loading = useMinimumDuration(isPending || sources === undefined || connectors === undefined);

	let body: ReactNode;
	// the undefined checks are repeated rather than left to the hook, which cannot narrow the type
	if (loading || sources === undefined || connectors === undefined) {
		body = <FeedSkeleton />;
	}
	else {
		const offered = connectors.filter((connector) => !connector.connected);
		body = (
			<div className="grid gap-4 sm:grid-cols-2">
				{sources.map((source) => (
					<SourceSummary key={source.source} source={source} counts={summaryFor(summary, source.source)} />
				))}
				{offered.map((connector) => (
					<ConnectCard key={connector.source} connector={connector} />
				))}
			</div>
		);
	}

	return (
		<Page title="Home" description={sources?.length === 0 ? "Connect a source to start." : "What is new, and where."}>
			<SourceAlerts />
			{body}
		</Page>
	);
}

import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { useFeed } from "../feed/feed";
import { FeedList } from "../feed/FeedList";
import { FeedSkeleton } from "../feed/FeedSkeleton";
import { Page } from "../layout/Page";
import { SourceAlerts } from "../sources/SourceAlerts";
import { useSources } from "../sources/sources";

export function HomePage() {
	const { data: items, isPending } = useFeed();
	const { data: sources } = useSources();
	const nothingConnected = sources !== undefined && sources.length === 0;
	const empty = nothingConnected ? <NothingConnected /> : <NothingWaiting />;

	return (
		<Page title="Home" description="Everything that needs you, from every source at once.">
			<SourceAlerts />
			{isPending ? <FeedSkeleton /> : <FeedList items={items ?? []} empty={empty} />}
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

function NothingWaiting() {
	return (
		<EmptyState
			title="Nothing needs you"
			description="Everything Sift can see has been dealt with. New items appear here on their own."
		/>
	);
}

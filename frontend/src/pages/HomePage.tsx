import { Link } from "react-router";
import { EmptyState } from "../components/EmptyState";
import { Page } from "../layout/Page";

export function HomePage() {
	return (
		<Page title="Home" description="Everything that needs you, from every source at once.">
			<EmptyState
				title="Nothing to show yet"
				description="Connect GitLab and the things that name you, assign you, or ask for your review will land here."
				action={
					<Link
						to="/settings"
						className="text-[13px] text-fg underline decoration-border underline-offset-4 hover:decoration-accent"
					>
						Go to settings
					</Link>
				}
			/>
		</Page>
	);
}

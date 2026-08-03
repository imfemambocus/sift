import { EmptyState } from "../components/EmptyState";
import { Page } from "../layout/Page";

export function GitLabPage() {
	return (
		<Page title="GitLab" description="Todos, review requests and mentions from your GitLab instance.">
			<EmptyState
				title="No GitLab account connected"
				description="Sift reads your GitLab to-do list, which is already scoped to you, rather than everything happening in your projects. Connecting it is the next thing being built."
			/>
		</Page>
	);
}

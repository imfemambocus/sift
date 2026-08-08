import { EmptyState } from "../components/EmptyState";
import { SourceFeedPage } from "./SourceFeedPage";

export function GitLabPage() {
	return (
		<SourceFeedPage
			source="gitlab"
			description="To-dos, review requests and mentions from your GitLab instance."
			offer="Sift reads your GitLab to-do list, which is already scoped to you, rather than everything happening in your projects."
			allClear={
				<EmptyState
					title="Your GitLab to-do list is empty"
					description="Nothing there is waiting on you. Anything new shows up here within a few minutes."
				/>
			}
		/>
	);
}

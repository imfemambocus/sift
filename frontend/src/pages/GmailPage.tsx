import { EmptyState } from "../components/EmptyState";
import { SourceFeedPage } from "./SourceFeedPage";

export function GmailPage() {
	return (
		<SourceFeedPage
			source="gmail"
			description="Every message, in one list, with a search that forgives a typo."
			offer="Sift brings your mail into the same list as everything else, so one search covers all of it."
			allClear={
				<EmptyState
					title="No mail yet"
					description="Nothing has arrived since Sift last looked. Anything new shows up here within a few minutes."
				/>
			}
		/>
	);
}

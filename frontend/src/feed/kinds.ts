/*
 * the backend stores the source's own action token so rules can match on it, and the wording lives
 * here. an action GitLab adds later gets a readable label from the fallback rather than nothing.
 */
const LABELS: Record<string, string> = {
	assigned: "Assigned to you",
	review_requested: "Review requested",
	approval_required: "Approval required",
	directly_addressed: "You were addressed",
	mentioned: "You were mentioned",
	build_failed: "Pipeline failed",
	unmergeable: "Cannot be merged",
	merge_train_removed: "Removed from the merge train",
	review_submitted: "Review submitted",
	okr_checkin_requested: "Check-in requested",
	marked: "You marked this",
	member_access_requested: "Access requested",
};

export function kindLabel(kind: string): string {
	const known = LABELS[kind];
	if (known !== undefined) {
		return known;
	}
	const words = kind.replace(/_/g, " ").trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

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
	// read from the merge request list rather than a to-do, so the wording says state not event
	mr_review_requested: "Waiting for your review",
	mr_assigned: "Assigned to you",

	// participation: one row per thread, so the plural is honest
	new_thread: "New thread",
	new_comment: "New replies",
	changes_pushed: "Changes pushed",
	mr_merged: "Merged",

	// mail: one row per message, so the wording says what happened rather than what it wants
	mail_received: "New message",
};

export function kindLabel(kind: string): string {
	const known = LABELS[kind];
	if (known !== undefined) {
		return known;
	}
	const words = kind.replace(/_/g, " ").trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

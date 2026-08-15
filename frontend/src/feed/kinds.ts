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
	/*
	 * GitLab's own to-do for a failed build, which can share a group with the pipeline row below it.
	 * it keeps the to-do's word so the two are told apart: one is a to-do you can dismiss on GitLab,
	 * the other is the verdict Sift watched arrive.
	 */
	build_failed: "Build failed",
	unmergeable: "Cannot be merged",
	merge_train_removed: "Removed from the merge train",
	review_submitted: "Review submitted",
	okr_checkin_requested: "Check-in requested",
	marked: "You marked this",
	member_access_requested: "Access requested",
	/*
	 * read from the merge request list rather than a to-do: the wording says state, not event. it
	 * must also differ from the `assigned` to-do above, which can share a group with it.
	 */
	mr_review_requested: "Waiting for your review",
	mr_assigned: "Waiting on you",

	// participation: one row per thread, which is what makes the plural honest
	new_thread: "New thread",
	new_comment: "New replies",
	changes_pushed: "Changes pushed",
	mr_approved: "Approved",
	mr_merged: "Merged",
	pipeline_failed: "Pipeline failed",
	pipeline_fixed: "Pipeline fixed",

	// mail: one row per message. the wording says what happened, not what it wants
	mail_received: "New message",
	mail_sent: "Sent",
};

/*
 * mail is the one source where every row is the same kind. printing that kind on each row says
 * nothing at all. sent mail keeps its name: that one tells two rows apart.
 */
const UNNAMED_ON_A_ROW = new Set(["mail_received"]);

export function namesItsKind(kind: string): boolean {
	return !UNNAMED_ON_A_ROW.has(kind);
}

export function kindLabel(kind: string): string {
	const known = LABELS[kind];
	if (known !== undefined) {
		return known;
	}
	const words = kind.replace(/_/g, " ").trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

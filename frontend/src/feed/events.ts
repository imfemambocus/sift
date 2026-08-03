/*
 * Kinds are the source's own vocabulary and there are already a dozen of them, with more coming
 * from participation. A colour per kind would be an unreadable legend, so they collapse into six
 * families that answer "why is this in my list": someone wants my review, it is mine, I was named,
 * a discussion moved, something is broken, or none of those.
 */
export type EventFamily = "review" | "assigned" | "mention" | "discussion" | "blocked" | "other";

const FAMILY_BY_KIND: Record<string, EventFamily> = {
	review_requested: "review",
	mr_review_requested: "review",
	approval_required: "review",
	review_submitted: "review",
	// commits landing on something you are reviewing means look again, so it belongs with review
	changes_pushed: "review",

	assigned: "assigned",
	mr_assigned: "assigned",

	mentioned: "mention",
	directly_addressed: "mention",

	new_thread: "discussion",
	new_comment: "discussion",

	build_failed: "blocked",
	unmergeable: "blocked",
	merge_train_removed: "blocked",

	marked: "other",
	member_access_requested: "other",
};

export const FAMILY_ORDER: readonly EventFamily[] = [
	"review",
	"assigned",
	"mention",
	"discussion",
	"blocked",
	"other",
];

export const FAMILY_LABEL: Record<EventFamily, string> = {
	review: "Needs review",
	assigned: "Assigned to you",
	mention: "You were named",
	discussion: "Discussion moved",
	blocked: "Something broke",
	other: "Everything else",
};

export const FAMILY_EDGE: Record<EventFamily, string> = {
	review: "border-l-event-review",
	assigned: "border-l-event-assigned",
	mention: "border-l-event-mention",
	discussion: "border-l-event-discussion",
	blocked: "border-l-event-blocked",
	other: "border-l-event-other",
};

export const FAMILY_FILL: Record<EventFamily, string> = {
	review: "bg-event-review",
	assigned: "bg-event-assigned",
	mention: "bg-event-mention",
	discussion: "bg-event-discussion",
	blocked: "bg-event-blocked",
	other: "bg-event-other",
};

export function eventFamily(kind: string): EventFamily {
	return FAMILY_BY_KIND[kind] ?? "other";
}

/*
 * Only the ends of the scale are labelled. Writing "Normal" on almost every row is noise, and its
 * absence is the clearest way to say "nothing special about this one".
 */
const PRIORITY_LABEL: Record<string, string> = { HIGH: "High", LOW: "Low" };
const PRIORITY_CLASS: Record<string, string> = { HIGH: "text-accent", LOW: "text-fg-muted/55" };

export function priorityBadge(priority: string): { label: string; className: string } | null {
	const label = PRIORITY_LABEL[priority];
	if (label === undefined) {
		return null;
	}
	return { label, className: PRIORITY_CLASS[priority] ?? "text-fg-muted" };
}

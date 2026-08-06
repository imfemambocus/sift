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
	// merged is the one row that wants nothing from you, so it stays the quiet grey
	mr_merged: "other",
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

/*
 * the family colours the wording of the kind rather than an edge on the row. the left edge says
 * whether a row is unread, which is the one thing worth the app's own accent.
 */
export const FAMILY_TEXT: Record<EventFamily, string> = {
	review: "text-event-review",
	assigned: "text-event-assigned",
	mention: "text-event-mention",
	discussion: "text-event-discussion",
	blocked: "text-event-blocked",
	other: "text-event-other",
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

/**
 * What a row in the list wears, which is not the same question as what its kind means.
 *
 * <p>A resolved row is history: the to-do was completed, the merge request was merged. It wants
 * nothing from anyone, so it reads as the quiet grey whatever it was asking for while it was live.
 * Both the plain row and an event inside a group go through here so the two cannot drift.
 */
export function rowFamily(kind: string, resolved: boolean): EventFamily {
	return resolved ? "other" : eventFamily(kind);
}

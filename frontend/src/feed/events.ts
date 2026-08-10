/*
 * Kinds are the source's own vocabulary and there are already a dozen of them, with more coming
 * from participation. A colour per kind would be an unreadable legend, so they collapse into seven
 * families that answer "why is this in my list": someone wants my review, it is mine, I was named,
 * a discussion moved, something is broken, a message arrived, or none of those.
 *
 * `message` earns its own hue rather than borrowing one. Mail is not the quiet grey of `other`,
 * which is for a row that wants nothing from anybody, and it is not `discussion` either: nothing
 * moved, something arrived. It is also the family that dominates a connected mailbox.
 */
export type EventFamily =
	| "review"
	| "assigned"
	| "mention"
	| "discussion"
	| "blocked"
	| "message"
	| "other";

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

	mail_received: "message",
	mail_sent: "message",

	build_failed: "blocked",
	unmergeable: "blocked",
	merge_train_removed: "blocked",

	marked: "other",
	member_access_requested: "other",
	// approved and merged report an outcome and want nothing from you, so they stay the quiet grey
	mr_approved: "other",
	mr_merged: "other",
};

export const FAMILY_ORDER: readonly EventFamily[] = [
	"review",
	"assigned",
	"mention",
	"discussion",
	"blocked",
	"message",
	"other",
];

export const FAMILY_LABEL: Record<EventFamily, string> = {
	review: "Needs review",
	assigned: "Assigned to you",
	mention: "You were named",
	discussion: "Discussion moved",
	blocked: "Something broke",
	message: "Mail arrived",
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
	message: "text-event-message",
	other: "text-event-other",
};

/*
 * the same hue held back, for a row that has no kind wording to carry it. a name is read as a name
 * first, so it takes less colour than a word whose whole job is to say what kind of row this is.
 * written out rather than composed, because tailwind reads these class names out of the source.
 */
export const FAMILY_TEXT_SOFT: Record<EventFamily, string> = {
	review: "text-event-review/70",
	assigned: "text-event-assigned/70",
	mention: "text-event-mention/70",
	discussion: "text-event-discussion/70",
	blocked: "text-event-blocked/70",
	message: "text-event-message/70",
	other: "text-event-other/70",
};

export const FAMILY_FILL: Record<EventFamily, string> = {
	review: "bg-event-review",
	assigned: "bg-event-assigned",
	mention: "bg-event-mention",
	discussion: "bg-event-discussion",
	blocked: "bg-event-blocked",
	message: "bg-event-message",
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

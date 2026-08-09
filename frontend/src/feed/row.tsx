import { Check, Circle, Paperclip } from "lucide-react";
import type { ReactNode } from "react";

/*
 * the pieces a feed row and a grouped one both wear, kept here so the two cannot drift apart: the
 * left edge is the unread marker and the tick is how you clear it by hand.
 */

export const ROW_MOTION = {
	hidden: { opacity: 0, y: -4 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: "easeOut" } },
} as const;

/** Exported because `SiftedPanel` wears the real row treatment, and it must not drift from it. */
export const EDGE_UNREAD = "border-l-accent";
// grey rather than the theme's border colour, which is faint enough to read as no edge at all
const EDGE_READ = "border-l-fg-muted/30";

export function edgeClass(read: boolean): string {
	return read ? EDGE_READ : EDGE_UNREAD;
}

/*
 * inside a group the edge is spent on the group as a whole, so an event within one says unread with a
 * dot instead. a second 2px edge a few pixels to the right of the first reads as a misaligned row
 * rather than as a child of it, which is the whole thing this avoids.
 */
export function UnreadDot({ read }: { readonly read: boolean }) {
	return (
		<span
			aria-hidden
			className={`mt-1.75 size-1.5 flex-none rounded-full ${read ? "bg-fg-muted/40" : "bg-accent"}`}
		/>
	);
}

type ReadToggleProps = {
	readonly read: boolean;
	readonly label: string;
	readonly onToggle: () => void;
	/** For an event inside a group, whose lines are shorter than a row's title. */
	readonly tight?: boolean;
};

/** Always a sibling of the row's link, never inside it: a button nested in an anchor is invalid. */
export function ReadToggle({ read, label, onToggle, tight = false }: ReadToggleProps) {
	// whole strings rather than an appended override, which tailwind cannot resolve by order
	const offset = tight ? "mt-1 mr-2" : "mt-2.5 mr-2";

	return (
		<button
			type="button"
			onClick={onToggle}
			aria-label={label}
			className={`${offset} flex size-7 flex-none items-center justify-center rounded-control text-fg-muted/60 transition-colors hover:text-fg`}
		>
			{read ? <Circle size={13} strokeWidth={1.75} /> : <Check size={14} strokeWidth={2} />}
		</button>
	);
}

/*
 * a row the source has stopped reporting. the greyed kind wording says it is no longer live, and this
 * says what became of it, which the kind alone cannot: "Assigned to you" is past tense once the to-do
 * has been completed.
 */
export function DoneTag() {
	return <span className="font-mono text-[11px] text-fg-muted/70">done</span>;
}

/*
 * what came with the message, which is half of why a mailbox is searched at all. the first name is
 * worth the room because it says why a row matched a search for it; the rest are a count and a
 * tooltip.
 *
 * an inline element rather than a flex box, because MetaLine draws its separator as a ::before on
 * this element, and inside a flex box that dot becomes a flex item with its own spacing.
 */
export function Attachments({ names }: { readonly names: readonly string[] }) {
	const first = names[0];
	if (first === undefined) {
		return null;
	}

	return (
		<span title={names.join("\n")} className="font-mono text-[11px] whitespace-nowrap">
			<Paperclip aria-hidden size={11} strokeWidth={1.75} className="mr-1 inline align-[-1px]" />
			<span className="sr-only">Attached: </span>
			<span className="inline-block max-w-44 truncate align-bottom">{first}</span>
			{names.length > 1 && <span className="text-fg-muted/70"> +{names.length - 1}</span>}
		</span>
	);
}

/*
 * the small facts under a title. css draws the separator between one part and the next, so a part
 * that is absent takes its separator with it and no part has to know what comes before it.
 *
 * children must be plain elements rather than fragments, since the rule selects direct children.
 */
export function MetaLine({ children }: { readonly children: ReactNode }) {
	return (
		<span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[12px] text-fg-muted [&>*+*]:before:mr-2 [&>*+*]:before:text-fg-muted/45 [&>*+*]:before:content-['·']">
			{children}
		</span>
	);
}

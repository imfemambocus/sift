import { ChevronDown, ChevronRight } from "lucide-react";
import { motion } from "motion/react";
import { useId, useState } from "react";
import type { FeedItem } from "./feed";
import { useSetRead } from "./feed";
import { eventFamily, FAMILY_TEXT } from "./events";
import type { FeedGroup } from "./grouping";
import { groupUnread } from "./grouping";
import { kindLabel } from "./kinds";
import { Dot, edgeClass, ReadToggle, ROW_MOTION, UnreadDot } from "./row";
import { fullTimestamp, shortAgo } from "../lib/time";

/*
 * height 0 to auto, so collapsing reads as the events folding back into the row rather than the list
 * below jumping up. `MotionConfig reducedMotion="user"` in App.tsx drops it to a cut for anyone who
 * asked the OS for that, so there is nothing to check here.
 */
const EVENTS_MOTION = {
	open: { height: "auto", opacity: 1 },
	closed: { height: 0, opacity: 0 },
} as const;

const EVENTS_TRANSITION = { duration: 0.2, ease: "easeOut" } as const;

/*
 * one merge request that was assigned to you, replied to twice and then merged is one thing to look
 * at, not four. the title is said once at the top and each event gets a line underneath it.
 *
 * the 2px edge runs down the whole group rather than per row, so a group occupies one place in the
 * list exactly as a single row does; the events inside carry a dot instead. they are still read one
 * at a time, so each keeps its own tick.
 *
 * open by default: collapsing it is a way to put something aside, not the state you start in.
 */
export function FeedGroupRow({ group }: { readonly group: FeedGroup }) {
	const [open, setOpen] = useState(true);
	const eventsId = useId();
	const setRead = useSetRead();

	const unread = groupUnread(group);
	const { lead } = group;

	function toggleAll() {
		const read = unread > 0;
		const ids = group.items.filter((item) => item.read !== read).map((item) => item.id);
		setRead.mutate({ ids, read });
	}

	function markAllReadOnOpen() {
		const ids = group.items.filter((item) => !item.read).map((item) => item.id);
		if (ids.length > 0) {
			setRead.mutate({ ids, read: true });
		}
	}

	return (
		<motion.div
			variants={ROW_MOTION}
			className={`flex flex-col border-l-2 transition-colors ${edgeClass(unread === 0)}`}
		>
			<div className="flex items-start transition-colors hover:bg-raised">
				<a
					href={lead.url}
					target="_blank"
					rel="noreferrer"
					// opening the thing is reading everything that happened on it
					onClick={markAllReadOnOpen}
					className="flex min-w-0 flex-1 flex-col gap-1 py-2.5 pl-4 pr-2"
				>
					<span className={`text-[13.5px] leading-snug ${unread === 0 ? "text-fg-muted" : "font-medium text-fg"}`}>
						{lead.title}
					</span>

					<span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[12px] text-fg-muted">
						{lead.contextLabel !== null && (
							<>
								<span className="font-mono text-[11px]">{lead.contextLabel}</span>
								<Dot />
							</>
						)}
						<span>{group.items.length} updates</span>
						<Dot />
						<time
							dateTime={lead.activityAt}
							title={`Last activity ${fullTimestamp(lead.activityAt)}`}
							className="font-mono text-[11px]"
						>
							{shortAgo(lead.activityAt)}
						</time>
					</span>
				</a>

				<button
					type="button"
					onClick={() => setOpen(!open)}
					aria-expanded={open}
					aria-controls={eventsId}
					aria-label={open ? `Collapse what happened on "${lead.title}"` : `Expand what happened on "${lead.title}"`}
					className="mt-2.5 flex size-7 flex-none items-center justify-center rounded-control text-fg-muted/60 transition-colors hover:text-fg"
				>
					{open ? <ChevronDown size={14} strokeWidth={2} /> : <ChevronRight size={14} strokeWidth={2} />}
				</button>

				<ReadToggle
					read={unread === 0}
					label={
						unread === 0
							? `Mark everything on "${lead.title}" as unread`
							: `Mark everything on "${lead.title}" as read`
					}
					onToggle={toggleAll}
				/>
			</div>

			{/*
			  * `inert` rather than unmounting: it keeps the collapsed events out of the tab order and
			  * the a11y tree, which overflow-hidden alone does not, while leaving them there to animate
			  * and leaving aria-controls pointing at something real.
			  *
			  * initial={false} because a group starts open, and without it every group in the list would
			  * slide itself open on first paint and fight the list's own staggered entry.
			  */}
			<motion.div
				id={eventsId}
				inert={!open}
				initial={false}
				animate={open ? "open" : "closed"}
				variants={EVENTS_MOTION}
				transition={EVENTS_TRANSITION}
				className="overflow-hidden"
			>
				<div className="flex flex-col pb-1">
					{group.items.map((item) => (
						<GroupedEvent key={item.id} item={item} />
					))}
				</div>
			</motion.div>
		</motion.div>
	);
}

/** The title is already above it, so what this line is for is which event it was, and when. */
function GroupedEvent({ item }: { readonly item: FeedItem }) {
	const family = eventFamily(item.kind);
	const setRead = useSetRead();

	function toggleRead() {
		setRead.mutate({ ids: [item.id], read: !item.read });
	}

	function markReadOnOpen() {
		if (!item.read) {
			setRead.mutate({ ids: [item.id], read: true });
		}
	}

	return (
		<div className="flex items-start transition-colors hover:bg-raised">
			{/* the dot sits well right of the title above it, so the pair reads as heading and list */}
			<a
				href={item.url}
				target="_blank"
				rel="noreferrer"
				onClick={markReadOnOpen}
				className="flex min-w-0 flex-1 items-start gap-2 py-1 pl-6 pr-2"
			>
				<UnreadDot read={item.read} />

				<span className="flex min-w-0 flex-col gap-0.5">
					<span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[12px] text-fg-muted">
						<span className={`${item.read ? "" : "font-medium"} ${FAMILY_TEXT[family]}`}>{kindLabel(item.kind)}</span>

						{item.actorName !== null && (
							<>
								<Dot />
								<span>{item.actorName}</span>
							</>
						)}

						<Dot />
						<time
							dateTime={item.activityAt}
							title={`Last activity ${fullTimestamp(item.activityAt)}\nCreated ${fullTimestamp(item.createdAt)}`}
							className="font-mono text-[11px]"
						>
							{shortAgo(item.activityAt)}
						</time>
					</span>

					{item.body !== null && (
						<span className="line-clamp-2 text-[12.5px] leading-snug text-fg-muted">{item.body}</span>
					)}
				</span>
			</a>

			<ReadToggle
				tight
				read={item.read}
				label={item.read ? `Mark "${kindLabel(item.kind)}" as unread` : `Mark "${kindLabel(item.kind)}" as read`}
				onToggle={toggleRead}
			/>
		</div>
	);
}

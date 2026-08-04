import { Check, Circle } from "lucide-react";
import { motion } from "motion/react";
import type { FeedItem } from "./feed";
import { useSetRead } from "./feed";
import { eventFamily, FAMILY_TEXT } from "./events";
import { kindLabel } from "./kinds";
import { fullTimestamp, shortAgo } from "../lib/time";

const ROW = {
	hidden: { opacity: 0, y: -4 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: "easeOut" } },
} as const;

const EDGE_UNREAD = "border-l-accent";
// grey rather than the theme's border colour, which is faint enough to read as no edge at all
const EDGE_READ = "border-l-fg-muted/30";

/*
 * the left edge says unread, the one thing in a list worth spending the app's accent on. why the
 * row is here is the coloured wording next to the timestamp, which is a name rather than a hue to
 * decode.
 */
export function FeedRow({ item }: { readonly item: FeedItem }) {
	const family = eventFamily(item.kind);
	const setRead = useSetRead();

	function toggleRead() {
		setRead.mutate({ id: item.id, read: !item.read });
	}

	function markReadOnOpen() {
		if (!item.read) {
			setRead.mutate({ id: item.id, read: true });
		}
	}

	return (
		<motion.div
			variants={ROW}
			className={`flex items-start border-l-2 transition-colors hover:bg-raised ${item.read ? EDGE_READ : EDGE_UNREAD}`}
		>
			<a
				href={item.url}
				target="_blank"
				rel="noreferrer"
				// opening it is reading it; the toggle is there for anything you decide to skip
				onClick={markReadOnOpen}
				className="flex min-w-0 flex-1 flex-col gap-1 py-2.5 pl-4 pr-2"
			>
				<span className={`text-[13.5px] leading-snug ${item.read ? "text-fg-muted" : "font-medium text-fg"}`}>
					{item.title}
				</span>

				{/* the comment that arrived, which for a discussion row is the whole point */}
				{item.body !== null && (
					<span className="line-clamp-2 text-[12.5px] leading-snug text-fg-muted">{item.body}</span>
				)}

				<span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[12px] text-fg-muted">
					{/* each optional part carries its own trailing separator, since the kind always follows */}
					{item.contextLabel !== null && (
						<>
							<span className="font-mono text-[11px]">{item.contextLabel}</span>
							<Dot />
						</>
					)}

					{item.actorName !== null && (
						<>
							<span>{item.actorName}</span>
							<Dot />
						</>
					)}

					<span className={`font-medium ${FAMILY_TEXT[family]}`}>{kindLabel(item.kind)}</span>

					<Dot />
					{/* the time shown is the last activity, not the creation date: an MR opened last
					    week that got commits an hour ago is an hour old as far as anyone cares */}
					<time
						dateTime={item.activityAt}
						title={`Last activity ${fullTimestamp(item.activityAt)}\nCreated ${fullTimestamp(item.createdAt)}`}
						className="font-mono text-[11px]"
					>
						{shortAgo(item.activityAt)}
					</time>
				</span>
			</a>

			{/* a sibling of the link, not inside it: a button nested in an anchor is invalid */}
			<button
				type="button"
				onClick={toggleRead}
				aria-label={item.read ? `Mark "${item.title}" as unread` : `Mark "${item.title}" as read`}
				className="mt-2.5 mr-2 flex size-7 flex-none items-center justify-center rounded-control text-fg-muted/60 transition-colors hover:text-fg"
			>
				{item.read ? <Circle size={13} strokeWidth={1.75} /> : <Check size={14} strokeWidth={2} />}
			</button>
		</motion.div>
	);
}

function Dot() {
	return (
		<span aria-hidden className="text-fg-muted/45">
			&middot;
		</span>
	);
}

import { motion } from "motion/react";
import type { FeedItem } from "./feed";
import { eventFamily, FAMILY_EDGE, priorityBadge } from "./events";
import { kindLabel } from "./kinds";
import { fullTimestamp, shortAgo } from "../lib/time";

const ROW = {
	hidden: { opacity: 0, y: -4 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: "easeOut" } },
} as const;

/*
 * the left edge carries the event family, since that is what you scan a list for. priority is a
 * word next to the actor instead: it qualifies the row rather than categorising it.
 */
export function FeedRow({ item }: { readonly item: FeedItem }) {
	const family = eventFamily(item.kind);
	const priority = priorityBadge(item.priority);

	return (
		<motion.a
			variants={ROW}
			href={item.url}
			target="_blank"
			rel="noreferrer"
			className={`group flex flex-col gap-1 border-l-2 py-2.5 pl-4 pr-3 transition-colors hover:bg-raised ${FAMILY_EDGE[family]}`}
		>
			<span className="text-[13.5px] leading-snug text-fg">{item.title}</span>

			{/* the comment that arrived, which for a discussion row is the whole point */}
			{item.body !== null && (
				<span className="line-clamp-2 text-[12.5px] leading-snug text-fg-muted">{item.body}</span>
			)}

			<span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-[12px] text-fg-muted">
				<span>{kindLabel(item.kind)}</span>

				{item.contextLabel !== null && (
					<>
						<Dot />
						<span className="font-mono text-[11px]">{item.contextLabel}</span>
					</>
				)}

				{item.actorName !== null && (
					<>
						<Dot />
						<span>{item.actorName}</span>
					</>
				)}

				{priority !== null && (
					<>
						<Dot />
						<span className={`font-mono text-[10px] font-medium uppercase tracking-[0.1em] ${priority.className}`}>
							{priority.label}
						</span>
					</>
				)}

				<Dot />
				<time dateTime={item.createdAt} title={fullTimestamp(item.createdAt)} className="font-mono text-[11px]">
					{shortAgo(item.createdAt)}
				</time>
			</span>
		</motion.a>
	);
}

function Dot() {
	return (
		<span aria-hidden className="text-fg-muted/45">
			&middot;
		</span>
	);
}

import { motion } from "motion/react";
import type { FeedItem } from "./feed";
import { kindLabel } from "./kinds";
import { fullTimestamp, shortAgo } from "../lib/time";

/*
 * priority is one accent at three intensities rather than a second hue, so it never competes with
 * the interactive colour for meaning. an unfamiliar value renders as normal.
 */
const EDGE: Record<string, string> = {
	HIGH: "border-l-accent",
	NORMAL: "border-l-border",
	LOW: "border-l-transparent",
};

const ROW = {
	hidden: { opacity: 0, y: -4 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: "easeOut" } },
} as const;

export function FeedRow({ item }: { readonly item: FeedItem }) {
	const edge = EDGE[item.priority] ?? EDGE.NORMAL;

	return (
		<motion.a
			variants={ROW}
			href={item.url}
			target="_blank"
			rel="noreferrer"
			className={`group flex flex-col gap-1 border-l-2 py-2.5 pl-4 pr-3 transition-colors hover:bg-raised ${edge}`}
		>
			<span className="text-[13.5px] leading-snug text-fg">{item.title}</span>

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

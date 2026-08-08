import { motion } from "motion/react";
import type { FeedItem } from "./feed";
import { useSetRead } from "./feed";
import { FAMILY_TEXT, FAMILY_TEXT_SOFT, rowFamily } from "./events";
import { kindLabel, namesItsKind } from "./kinds";
import { DoneTag, edgeClass, MetaLine, ReadToggle, ROW_MOTION } from "./row";
import { fullTimestamp, shortAgo } from "../lib/time";

/*
 * the left edge says unread, the one thing in a list worth spending the app's accent on. why the
 * row is here is the coloured wording next to the timestamp, which is a name rather than a hue to
 * decode.
 */
export function FeedRow({ item }: { readonly item: FeedItem }) {
	const family = rowFamily(item.kind, item.resolved);
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
		<motion.div
			variants={ROW_MOTION}
			className={`flex items-start border-l-2 transition-colors hover:bg-raised ${edgeClass(item.read)}`}
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

				<MetaLine>
					{item.contextLabel !== null && (
						<span className="font-mono text-[11px]">{item.contextLabel}</span>
					)}

					{item.actorName !== null && (
						<span className={namesItsKind(item.kind) ? "" : FAMILY_TEXT_SOFT[family]}>{item.actorName}</span>
					)}

					{namesItsKind(item.kind) && (
						<span className={`font-medium ${FAMILY_TEXT[family]}`}>{kindLabel(item.kind)}</span>
					)}

					{item.resolved && <DoneTag />}

					{/* the time shown is the last activity, not the creation date: an MR opened last
					    week that got commits an hour ago is an hour old as far as anyone cares */}
					<time
						dateTime={item.activityAt}
						title={`Last activity ${fullTimestamp(item.activityAt)}\nCreated ${fullTimestamp(item.createdAt)}`}
						className="font-mono text-[11px]"
					>
						{shortAgo(item.activityAt)}
					</time>
				</MetaLine>
			</a>

			<ReadToggle
				read={item.read}
				label={item.read ? `Mark "${item.title}" as unread` : `Mark "${item.title}" as read`}
				onToggle={toggleRead}
			/>
		</motion.div>
	);
}

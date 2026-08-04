import { motion } from "motion/react";
import type { ReactNode } from "react";
import type { FeedItem } from "./feed";
import { FeedGroupRow } from "./FeedGroupRow";
import { FeedRow } from "./FeedRow";
import type { FeedGroup } from "./grouping";
import { intoGroups } from "./grouping";
import { dayGroup } from "../lib/time";

type Section = { readonly label: string; readonly groups: readonly FeedGroup[] };

/** Preserves the order the API returned, which is already newest first. */
function intoDays(groups: readonly FeedGroup[]): Section[] {
	const sections: { label: string; groups: FeedGroup[] }[] = [];

	for (const group of groups) {
		// the day a group belongs to is when it last moved, so its older events sit under it
		const label = dayGroup(group.newest.activityAt);
		const current = sections.at(-1);
		if (current?.label === label) {
			current.groups.push(group);
		}
		else {
			sections.push({ label, groups: [group] });
		}
	}
	return sections;
}

type FeedListProps = {
	readonly items: readonly FeedItem[];
	readonly empty: ReactNode;
};

export function FeedList({ items, empty }: FeedListProps) {
	if (items.length === 0) {
		return <>{empty}</>;
	}

	return (
		<motion.div
			initial="hidden"
			animate="visible"
			variants={{ visible: { transition: { staggerChildren: 0.02 } } }}
			className="flex flex-col gap-7"
		>
			{intoDays(intoGroups(items)).map((section) => (
				<section key={section.label} className="flex flex-col gap-2">
					<h2 className="eyebrow pl-4">{section.label}</h2>
					{/* a hairline gap so a run of unread edges reads as one marker each, rather than
					    merging into a single bar that looks like a section bracket */}
					<div className="flex flex-col gap-1">
						{section.groups.map((group) => (
							<GroupOrRow key={group.key} group={group} />
						))}
					</div>
				</section>
			))}
		</motion.div>
	);
}

/*
 * one event on a thing is just a row: collapsing a single item under a disclosure would be a lot of
 * chrome to say nothing, and most of the feed is single items.
 */
function GroupOrRow({ group }: { readonly group: FeedGroup }) {
	if (group.items.length === 1) {
		return <FeedRow item={group.newest} />;
	}
	return <FeedGroupRow group={group} />;
}

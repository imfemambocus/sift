import { motion } from "motion/react";
import type { ReactNode } from "react";
import { useState } from "react";
import type { FeedItem } from "./feed";
import { FeedGroupRow } from "./FeedGroupRow";
import { FeedRow } from "./FeedRow";
import type { FeedGroup } from "./grouping";
import { intoGroups } from "./grouping";
import { dayGroup } from "../lib/time";

/*
 * paged over groups, never over items, so a merge request's events cannot be split across the boundary
 * with half of them behind a button.
 *
 * this bounds what is rendered, not what is transferred: the whole working set is still fetched in one
 * request, because the search field and the tab badge are over all of it. server-side paging is the next
 * move if the corpus ever outgrows shipping it, and `GET /api/feed?source=` is already there for it.
 */
const PAGE = 50;

type Section = { readonly label: string; readonly groups: readonly FeedGroup[] };

/** Preserves the order it was handed, which the page has already filtered and sorted. */
function intoDays(groups: readonly FeedGroup[]): Section[] {
	const sections: { label: string; groups: FeedGroup[] }[] = [];

	for (const group of groups) {
		// the day a group belongs to is the one its leading event is in, so the rest sit under it
		const label = dayGroup(group.lead.activityAt);
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
	/*
	 * deliberately not reset when the filter or the query changes. it is "how much am I willing to
	 * see", which survives narrowing perfectly well, and resetting it would mean either remounting the
	 * list on every keystroke of a search or replaying the entry animation each time.
	 */
	const [limit, setLimit] = useState(PAGE);

	if (items.length === 0) {
		return <>{empty}</>;
	}

	const groups = intoGroups(items);
	const shown = groups.slice(0, limit);

	return (
		<div className="flex flex-col gap-7">
			<motion.div
				initial="hidden"
				animate="visible"
				variants={{ visible: { transition: { staggerChildren: 0.02 } } }}
				className="flex flex-col gap-7"
			>
				{intoDays(shown).map((section) => (
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

			{groups.length > shown.length && (
				<div className="flex items-center gap-3 pl-4">
					<button
						type="button"
						onClick={() => setLimit(limit + PAGE)}
						className="rounded-control border border-border px-3 py-1.5 text-[12px] text-fg transition-colors hover:bg-raised"
					>
						Show more
					</button>
					<span className="font-mono text-[11px] text-fg-muted">
						{shown.length} of {groups.length}
					</span>
				</div>
			)}
		</div>
	);
}

/*
 * one event on a thing is just a row: collapsing a single item under a disclosure would be a lot of
 * chrome to say nothing, and most of the feed is single items.
 */
function GroupOrRow({ group }: { readonly group: FeedGroup }) {
	if (group.items.length === 1) {
		return <FeedRow item={group.lead} />;
	}
	return <FeedGroupRow group={group} />;
}

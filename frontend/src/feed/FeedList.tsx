import { motion } from "motion/react";
import type { ReactNode } from "react";
import type { FeedItem } from "./feed";
import { FeedGroupRow } from "./FeedGroupRow";
import { FeedRow } from "./FeedRow";
import type { FeedGroup } from "./grouping";
import { intoGroups } from "./grouping";
import { dayGroup } from "../lib/time";

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

/*
 * paged over groups, never over items, so a merge request's events cannot be split across the
 * boundary with half of them behind a button. the server does that paging and hands back whole
 * groups, so this asks for the next page rather than slicing a list it is already holding.
 */
type FeedListProps = {
	readonly items: readonly FeedItem[];
	readonly empty: ReactNode;
	readonly hasMore?: boolean;
	readonly onMore?: () => void;
	readonly loadingMore?: boolean;
};

export function FeedList({ items, empty, hasMore = false, onMore, loadingMore = false }: FeedListProps) {
	if (items.length === 0) {
		return <>{empty}</>;
	}

	const shown = intoGroups(items);

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

			{hasMore && (
				<div className="flex items-center gap-3 pl-4">
					<button
						type="button"
						onClick={onMore}
						disabled={loadingMore}
						className="rounded-control border border-border px-3 py-1.5 text-[12px] text-fg transition-colors hover:bg-raised disabled:cursor-not-allowed disabled:opacity-55"
					>
						{loadingMore ? "Loading" : "Show more"}
					</button>
					{/* how much is on screen, not how much there is: the total is a count the page
					    would have to ask for separately, and nothing here needs it */}
					<span className="font-mono text-[11px] text-fg-muted">{shown.length} shown</span>
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

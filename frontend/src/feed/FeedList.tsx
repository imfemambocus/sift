import { motion } from "motion/react";
import type { ReactNode } from "react";
import type { FeedItem } from "./feed";
import { FeedRow } from "./FeedRow";
import { dayGroup } from "../lib/time";

type Section = { readonly label: string; readonly items: readonly FeedItem[] };

/** Preserves the order the API returned, which is already newest first. */
function intoDays(items: readonly FeedItem[]): Section[] {
	const sections: { label: string; items: FeedItem[] }[] = [];

	for (const item of items) {
		const label = dayGroup(item.activityAt);
		const current = sections.at(-1);
		if (current?.label === label) {
			current.items.push(item);
		}
		else {
			sections.push({ label, items: [item] });
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
			{intoDays(items).map((section) => (
				<section key={section.label} className="flex flex-col gap-2">
					<h2 className="eyebrow pl-4">{section.label}</h2>
					{/* a hairline gap so a run of unread edges reads as one marker each, rather than
					    merging into a single bar that looks like a section bracket */}
					<div className="flex flex-col gap-1">
						{section.items.map((item) => (
							<FeedRow key={item.id} item={item} />
						))}
					</div>
				</section>
			))}
		</motion.div>
	);
}

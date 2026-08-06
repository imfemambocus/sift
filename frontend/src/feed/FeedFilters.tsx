import type { FeedSummary } from "./feed";
import type { FeedFilter } from "./view";

const TAB = "rounded-control px-2 py-1 text-[12px] transition-colors";
// whole strings rather than an appended override, which tailwind cannot resolve by class order
const TAB_ON = `${TAB} bg-raised text-fg`;
const TAB_OFF = `${TAB} text-fg-muted hover:text-fg`;

type FeedFiltersProps = {
	/** The counts come from the server now: the list on screen is one page of a longer history. */
	readonly counts: FeedSummary;
	readonly filter: FeedFilter;
	readonly onChange: (filter: FeedFilter) => void;
};

/**
 * The counts are the control. They used to be a read-only line saying "3 unread · 12 read", and the
 * numbers you would want to filter by were already the numbers on screen, so there was no reason to
 * put a second thing next to them.
 */
export function FeedFilters({ counts, filter, onChange }: FeedFiltersProps) {
	if (counts.total === 0) {
		return null;
	}

	const options: readonly { readonly value: FeedFilter; readonly label: string; readonly count: number }[] = [
		{ value: "all", label: "All", count: counts.total },
		{ value: "unread", label: "Unread", count: counts.unread },
		{ value: "read", label: "Read", count: counts.total - counts.unread },
	];

	return (
		<div role="group" aria-label="Filter the feed" className="flex items-center gap-0.5">
			{options.map((option) => (
				<button
					key={option.value}
					type="button"
					onClick={() => onChange(option.value)}
					aria-pressed={filter === option.value}
					className={filter === option.value ? TAB_ON : TAB_OFF}
				>
					{option.label}{" "}
					{/* brass on unread, the same thing the edge on a row means */}
					<span className={option.value === "unread" && option.count > 0 ? "text-accent" : "text-fg-muted"}>
						{option.count}
					</span>
				</button>
			))}
		</div>
	);
}

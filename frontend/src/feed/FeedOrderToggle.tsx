import { ArrowDownUp } from "lucide-react";
import type { FeedOrder } from "./view";
import { ORDER_LABEL } from "./view";

type FeedOrderToggleProps = {
	readonly order: FeedOrder;
	readonly onChange: (order: FeedOrder) => void;
};

/**
 * A toggle rather than a third row of tabs: there are two orders, and it has to look different from the
 * filter sitting next to it. See {@link FeedOrder} for why the list is short.
 */
export function FeedOrderToggle({ order, onChange }: FeedOrderToggleProps) {
	const next: FeedOrder = order === "latest" ? "waiting" : "latest";

	return (
		<button
			type="button"
			onClick={() => onChange(next)}
			aria-label={`Sorted by ${ORDER_LABEL[order].toLowerCase()}. Switch to ${ORDER_LABEL[next].toLowerCase()}.`}
			className="flex items-center gap-1.5 rounded-control px-2 py-1 text-[12px] text-fg-muted transition-colors hover:bg-raised hover:text-fg"
		>
			<ArrowDownUp size={12} strokeWidth={1.75} aria-hidden />
			{ORDER_LABEL[order]}
		</button>
	);
}

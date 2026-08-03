import type { FeedItem } from "./feed";
import { eventFamily, FAMILY_FILL, FAMILY_LABEL, FAMILY_ORDER } from "./events";

/*
 * only the families actually present are listed. a legend for colours that are not on screen is
 * just a colour chart, and it grows every time a source learns a new kind.
 */
export function EventLegend({ items }: { readonly items: readonly FeedItem[] }) {
	const present = new Set(items.map((item) => eventFamily(item.kind)));
	const shown = FAMILY_ORDER.filter((family) => present.has(family));

	if (shown.length < 2) {
		return null;
	}

	return (
		<div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
			{shown.map((family) => (
				<span key={family} className="flex items-center gap-1.5">
					<span aria-hidden className={`h-2.5 w-0.5 rounded-full ${FAMILY_FILL[family]}`} />
					<span className="text-[11px] text-fg-muted">{FAMILY_LABEL[family]}</span>
				</span>
			))}
		</div>
	);
}

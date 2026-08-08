import type { ReactNode } from "react";

/*
 * shared between the nav links and the plain buttons in the rail. colour is separate from the
 * base because tailwind cannot resolve two competing text-* utilities by attribute order.
 */
export const RAIL_BUTTON =
	"group relative flex size-10 items-center justify-center rounded-control transition-colors hover:bg-raised";
export const RAIL_ACTIVE = "text-fg";
export const RAIL_IDLE = "text-fg-muted hover:text-fg";

/** Above it the badge would say "lots" rather than a number, and it would widen past the icon. */
const MOST_SHOWN = 99;

/**
 * How many rows of one source are unread. Brass, like the left edge an unread row carries, so the
 * rail and the feed use one colour for one meaning.
 *
 * It is `aria-hidden` because the count belongs in the link's own label: a screen reader should say
 * "Gmail, 3 unread" once, not read a loose number sitting beside the name.
 */
export function RailBadge({ count }: { readonly count: number }) {
	if (count <= 0) {
		return null;
	}
	return (
		<span
			aria-hidden
			className="absolute top-1 right-1.5 min-w-4 rounded-full bg-accent px-1 text-center font-mono text-[10px] leading-4 font-medium text-accent-fg"
		>
			{count > MOST_SHOWN ? `${MOST_SHOWN}+` : count}
		</span>
	);
}

/** "3 unread" for a label, and nothing at all when there is nothing waiting. */
export function unreadSuffix(count: number): string {
	return count > 0 ? `, ${count} unread` : "";
}

export function RailTooltip({ children }: { readonly children: ReactNode }) {
	return (
		<span
			aria-hidden
			className="pointer-events-none absolute left-full z-20 ml-3 whitespace-nowrap rounded-control border border-border bg-surface px-2 py-1 text-xs text-fg opacity-0 shadow-lg transition-opacity group-hover:opacity-100"
		>
			{children}
		</span>
	);
}

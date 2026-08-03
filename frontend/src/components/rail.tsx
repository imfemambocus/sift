import type { ReactNode } from "react";

/*
 * shared between the nav links and the plain buttons in the rail. colour is separate from the
 * base because tailwind cannot resolve two competing text-* utilities by attribute order.
 */
export const RAIL_BUTTON =
	"group relative flex size-10 items-center justify-center rounded-control transition-colors hover:bg-raised";
export const RAIL_ACTIVE = "text-fg";
export const RAIL_IDLE = "text-fg-muted hover:text-fg";

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

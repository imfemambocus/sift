import { RefreshCw } from "lucide-react";
import { isReading, sourceName } from "./labels";
import type { SourceStatus } from "./sources";
import { useSyncSource } from "./sources";

/*
 * `relative` is load-bearing rather than layout: a Home card is one stretched link, whose overlay is
 * positioned and therefore paints over anything that is not. Without it the icon is visible and not
 * clickable, which is the worst of the two.
 */
const BUTTON =
	"relative flex size-7 items-center justify-center rounded-control text-fg-muted transition-colors hover:bg-raised hover:text-fg disabled:cursor-not-allowed";

/**
 * Reads a source again. One copy, because it sits on a feed page, on a Home card, and beside
 * whatever comes next.
 *
 * <p>It is always a sibling of the link it sits next to, never inside one: a button in an anchor is
 * invalid HTML, and clicking it would navigate as well.
 */
export function SyncButton({ source }: { readonly source: SourceStatus }) {
	const sync = useSyncSource(source.source);
	const name = sourceName(source.source);
	/*
	 * the icon turns for as long as the words next to it say the source is being read, including the
	 * gaps between the sweeps of a long history: the source is no nearer finished in a gap, so an icon
	 * that stopped there would say it had ended and then start again by itself.
	 */
	const spinning = sync.isPending || isReading(source);

	return (
		<button
			type="button"
			onClick={() => sync.mutate()}
			// only its own request disables it: a read already running is the answer somebody wanted
			disabled={sync.isPending}
			aria-label={sync.isPending ? `Checking ${name}` : `Check ${name} now`}
			className={BUTTON}
		>
			<RefreshCw size={14} strokeWidth={1.75} className={spinning ? "animate-spin" : ""} />
		</button>
	);
}

import { RefreshCw } from "lucide-react";
import { errorMessage } from "../lib/api";
import { sourceName, syncPhrase } from "./labels";
import type { SourceStatus } from "./sources";
import { useSyncSource } from "./sources";

/*
 * on a feed page the useful thing is how stale the list is, with reading it again offered rather
 * than announced. a bare "Check now" left people wondering whether it had already happened.
 */
export function LastSynced({ source }: { readonly source: SourceStatus }) {
	const sync = useSyncSource(source.source);
	const message = errorMessage(sync.error);
	const name = sourceName(source.source);
	/*
	 * the icon spins for a read this page did not start as well, since the sweep and the read that
	 * follows an approval both run on the server. only this button's own request disables it: pressing
	 * it during one of those is answered by the read already running, which is what was wanted anyway.
	 */
	const spinning = sync.isPending || source.syncing;

	return (
		<div className="flex flex-col items-end gap-1">
			<div className="flex items-center gap-1.5">
				<span className="text-[12px] text-fg-muted">{syncPhrase(source)}</span>
				<button
					type="button"
					onClick={() => sync.mutate()}
					disabled={sync.isPending}
					aria-label={sync.isPending ? `Checking ${name}` : `Check ${name} now`}
					className="flex size-7 items-center justify-center rounded-control text-fg-muted transition-colors hover:bg-raised hover:text-fg disabled:cursor-not-allowed"
				>
					<RefreshCw size={14} strokeWidth={1.75} className={spinning ? "animate-spin" : ""} />
				</button>
			</div>

			{message !== null && (
				<span role="alert" className="text-[11px] text-danger">
					{message}
				</span>
			)}
		</div>
	);
}

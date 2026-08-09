import { errorMessage } from "../lib/api";
import { syncPhrase } from "./labels";
import type { SourceStatus } from "./sources";
import { useSyncError } from "./sources";
import { SyncButton } from "./SyncButton";

/*
 * on a feed page the useful thing is how stale the list is, with reading it again offered rather
 * than announced. a bare "Check now" left people wondering whether it had already happened.
 */
export function LastSynced({ source }: { readonly source: SourceStatus }) {
	const message = errorMessage(useSyncError(source.source));

	return (
		<div className="flex flex-col items-end gap-1">
			<div className="flex items-center gap-1.5">
				<span className="text-[12px] text-fg-muted">{syncPhrase(source)}</span>
				<SyncButton source={source} />
			</div>

			{message !== null && (
				<span role="alert" className="text-[11px] text-danger">
					{message}
				</span>
			)}
		</div>
	);
}

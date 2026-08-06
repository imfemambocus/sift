import { Link } from "react-router";
import type { FeedSummary } from "../feed/feed";
import type { EventFamily } from "../feed/events";
import { eventFamily, FAMILY_FILL, FAMILY_LABEL, FAMILY_ORDER } from "../feed/events";
import { agoPhrase } from "../lib/time";
import { sourceName, sourcePath } from "../sources/labels";
import type { SourceStatus } from "../sources/sources";

/*
 * the server counts by kind and this turns those into families, rather than the server counting
 * families itself: which kinds make up a family is a question about how the app words things, and
 * that answer belongs on this side.
 */
function countByFamily(byKind: Readonly<Record<string, number>>): Record<EventFamily, number> {
	const counts = { review: 0, assigned: 0, mention: 0, discussion: 0, blocked: 0, other: 0 };
	for (const [kind, count] of Object.entries(byKind)) {
		counts[eventFamily(kind)] += count;
	}
	return counts;
}

type SourceSummaryProps = {
	readonly source: SourceStatus;
	readonly counts: FeedSummary;
};

export function SourceSummary({ source, counts }: SourceSummaryProps) {
	/*
	 * every number here is over what the source still reports, never the whole history. the feed
	 * holds a merged merge request; it does not belong in "11 waiting".
	 */
	const waiting = counts.waiting;
	const byFamily = countByFamily(counts.waitingByKind);
	const present = FAMILY_ORDER.filter((family) => byFamily[family] > 0);
	const rejected = source.status === "AUTH_FAILED";

	return (
		<Link
			to={sourcePath(source.source)}
			className="flex flex-col gap-4 rounded-panel border border-border bg-surface px-5 py-4 transition-colors hover:border-fg-muted/40"
		>
			<div className="flex items-baseline justify-between gap-3">
				<h2 className="text-[14px] font-semibold tracking-[-0.01em] text-fg">{sourceName(source.source)}</h2>
				{/* quiet unless something is actually wrong, so the card is not a wall of status */}
				{rejected && <span className="text-[11px] text-danger">Token rejected</span>}
			</div>

			<div className="flex items-baseline gap-2">
				<span className="text-[32px] font-semibold leading-none tracking-[-0.03em] text-fg">{waiting}</span>
				<span className="text-[13px] text-fg-muted">{waiting === 1 ? "item waiting" : "waiting"}</span>
			</div>

			{present.length > 0 && (
				<div className="flex flex-col gap-2.5">
					<div className="flex h-1 w-full overflow-hidden rounded-full">
						{present.map((family) => (
							<span
								key={family}
								className={FAMILY_FILL[family]}
								style={{ width: `${(byFamily[family] / waiting) * 100}%` }}
							/>
						))}
					</div>

					<div className="flex flex-wrap gap-x-3 gap-y-1">
						{present.map((family) => (
							<span key={family} className="flex items-center gap-1.5 text-[11px] text-fg-muted">
								<span aria-hidden className={`h-2 w-0.5 rounded-full ${FAMILY_FILL[family]}`} />
								{FAMILY_LABEL[family]}
								<span className="font-mono text-fg">{byFamily[family]}</span>
							</span>
						))}
					</div>
				</div>
			)}

			<div className="flex flex-wrap items-baseline gap-x-2 text-[12px] text-fg-muted">
				{counts.waitingUnread > 0 && <span className="text-accent">{counts.waitingUnread} unread</span>}
				{counts.waitingUnread > 0 && <span aria-hidden className="text-fg-muted/45">&middot;</span>}
				<span>{source.lastSyncAt === null ? "Not synced yet" : `Synced ${agoPhrase(source.lastSyncAt)}`}</span>
			</div>
		</Link>
	);
}

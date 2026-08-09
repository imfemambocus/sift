import { Link } from "react-router";
import type { FeedSummary } from "../feed/feed";
import type { EventFamily } from "../feed/events";
import { eventFamily, FAMILY_FILL, FAMILY_LABEL, FAMILY_ORDER } from "../feed/events";
import { sourceName, sourcePath, syncPhrase } from "../sources/labels";
import { SyncButton } from "../sources/SyncButton";
import type { SourceStatus } from "../sources/sources";

/*
 * the server counts by kind and this turns those into families, rather than the server counting
 * families itself: which kinds make up a family is a question about how the app words things, and
 * that answer belongs on this side.
 */
function countByFamily(byKind: Readonly<Record<string, number>>): Record<EventFamily, number> {
	const counts = { review: 0, assigned: 0, mention: 0, discussion: 0, blocked: 0, message: 0, other: 0 };
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
	 * the headline is unread, because the question a dashboard answers is "is there anything for me".
	 * waiting cannot answer it: reading a row here does not complete the to-do in GitLab, so a source
	 * that still reports 15 items reports them whether or not you have dealt with every one.
	 *
	 * it is counts.unread rather than counts.waitingUnread so this and the tab badge can never print
	 * different numbers. the two are equal anyway, since a row is stamped read the moment it resolves.
	 */
	const unread = counts.unread;
	// the breakdown below stays over what the source still reports: it is the shape of the workload
	const waiting = counts.waiting;
	const byFamily = countByFamily(counts.waitingByKind);
	const present = FAMILY_ORDER.filter((family) => byFamily[family] > 0);
	const rejected = source.status === "AUTH_FAILED";

	return (
		<article className="relative flex flex-col gap-4 rounded-panel border border-border bg-surface px-5 py-4 transition-colors hover:border-fg-muted/40">
			<div className="flex items-baseline justify-between gap-3">
				<h2 className="text-[14px] font-semibold tracking-[-0.01em] text-fg">
					{/*
					  * the whole card leads to the source, but the refresh button has to be a sibling of the
					  * link rather than inside it, so the link stretches over the card from here instead of
					  * wrapping it. the name is then the whole of what a screen reader announces for it.
					  */}
					<Link
						to={sourcePath(source.source)}
						className="after:absolute after:inset-0 after:content-['']"
					>
						{sourceName(source.source)}
					</Link>
				</h2>
				{/* quiet unless something is actually wrong, so the card is not a wall of status */}
				{rejected && <span className="text-[11px] text-danger">Token rejected</span>}
			</div>

			{/* brass while there is something, muted at zero: a nothing-state should read as calm */}
			<div className="flex items-baseline gap-2">
				<span
					className={`text-[32px] font-semibold leading-none tracking-[-0.03em] ${
						unread > 0 ? "text-accent" : "text-fg-muted"
					}`}
				>
					{unread}
				</span>
				<span className="text-[13px] text-fg-muted">{unread === 1 ? "unread item" : "unread"}</span>
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

			{/* waiting keeps its place as context, since it is what the breakdown above is counting */}
			<div className="flex items-center justify-between gap-3">
				<div className="flex flex-wrap items-baseline gap-x-2 text-[12px] text-fg-muted">
					<span>{waiting === 1 ? "1 waiting" : `${waiting} waiting`}</span>
					<span aria-hidden className="text-fg-muted/45">&middot;</span>
					<span>{syncPhrase(source)}</span>
				</div>
				{/* beside the line it refreshes, as on a feed page, and clear of the rest of the card */}
				<SyncButton source={source} />
			</div>
		</article>
	);
}

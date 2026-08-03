import { Link } from "react-router";
import { useSources } from "./sources";

const NEEDS_ATTENTION = new Set(["AUTH_FAILED", "ERROR"]);

/*
 * a source that has stopped working must say so where the feed is, not only in settings. the
 * failure mode this exists to prevent is an empty list that looks like good news.
 */
export function SourceAlerts({ only }: { readonly only?: string }) {
	const { data: sources } = useSources();

	const failing = (sources ?? [])
		.filter((source) => only === undefined || source.source === only)
		.filter((source) => NEEDS_ATTENTION.has(source.status));

	if (failing.length === 0) {
		return null;
	}

	return (
		<div className="flex flex-col gap-2">
			{failing.map((source) => {
				const rejected = source.status === "AUTH_FAILED";
				return (
					<p
						key={source.source}
						role="alert"
						className={`rounded-control border px-3 py-2 text-[13px] ${
							rejected ? "border-danger/35 bg-danger/8 text-danger" : "border-border bg-surface text-fg-muted"
						}`}
					>
						{rejected
							? `${source.source} rejected its token, so nothing new is arriving. `
							: `${source.source} could not be read on the last try. Sift will keep retrying. `}
						<Link to="/settings" className="text-fg underline decoration-border underline-offset-4">
							Open settings
						</Link>
					</p>
				);
			})}
		</div>
	);
}

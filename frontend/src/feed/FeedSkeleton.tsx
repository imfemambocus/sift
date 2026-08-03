const ROWS = [
	{ id: "s1", title: 62, meta: 34 },
	{ id: "s2", title: 48, meta: 28 },
	{ id: "s3", title: 55, meta: 31 },
];

/** Placeholder bars, which is the one place they honestly mean "loading". */
export function FeedSkeleton() {
	return (
		<div aria-hidden className="flex animate-pulse flex-col gap-4">
			{ROWS.map((row) => (
				<div key={row.id} className="flex flex-col gap-2 border-l-2 border-l-border pl-4">
					<span className="h-[10px] rounded-full bg-fg/10" style={{ width: `${row.title}%` }} />
					<span className="h-[8px] rounded-full bg-fg/10" style={{ width: `${row.meta}%` }} />
				</div>
			))}
		</div>
	);
}

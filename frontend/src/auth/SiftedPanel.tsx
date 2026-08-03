import { motion } from "motion/react";

/*
 * the panel is the product's argument, not decoration: a dense unreadable wall of what a source
 * sends you, thinning as it descends, resolving into the handful that actually names you. the two
 * eyebrows label the halves so the graphic reads as information rather than texture.
 *
 * the top rows are deliberately tight and near-invisible. spaced out they read as skeleton
 * loading placeholders instead of as too much.
 */

const NOISE_ROWS = [
	{ id: "n01", title: 318, meta: 62, opacity: 0.045, gap: 0 },
	{ id: "n02", title: 246, meta: 88, opacity: 0.05, gap: 3 },
	{ id: "n03", title: 292, meta: 54, opacity: 0.055, gap: 3 },
	{ id: "n04", title: 214, meta: 96, opacity: 0.06, gap: 3 },
	{ id: "n05", title: 330, meta: 48, opacity: 0.07, gap: 4 },
	{ id: "n06", title: 262, meta: 74, opacity: 0.08, gap: 4 },
	{ id: "n07", title: 198, meta: 58, opacity: 0.09, gap: 4 },
	{ id: "n08", title: 308, meta: 84, opacity: 0.1, gap: 5 },
	{ id: "n09", title: 238, meta: 50, opacity: 0.115, gap: 6 },
	{ id: "n10", title: 276, meta: 92, opacity: 0.13, gap: 7 },
	{ id: "n11", title: 186, meta: 66, opacity: 0.15, gap: 9 },
	{ id: "n12", title: 254, meta: 56, opacity: 0.17, gap: 11 },
	{ id: "n13", title: 208, meta: 80, opacity: 0.19, gap: 14 },
	{ id: "n14", title: 164, meta: 62, opacity: 0.21, gap: 17 },
] as const;

const SIFTED = [
	{ id: "s1", kind: "Review requested", target: "sift / backend", when: "12m", priority: "high" },
	{ id: "s2", kind: "You were mentioned", target: "sift / frontend", when: "1h", priority: "normal" },
	{ id: "s3", kind: "Pipeline failed on your branch", target: "sift / backend", when: "3h", priority: "normal" },
] as const;

const PRIORITY_EDGE: Record<"high" | "normal", string> = {
	high: "border-l-accent",
	normal: "border-l-border",
};

const ROW = {
	hidden: { opacity: 0, y: -8 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.45, ease: "easeOut" } },
} as const;

export function SiftedPanel() {
	return (
		<aside className="relative hidden items-center justify-center overflow-hidden border-l border-border bg-surface px-12 py-14 lg:flex">
			<motion.div
				initial="hidden"
				animate="visible"
				variants={{ visible: { transition: { staggerChildren: 0.025, delayChildren: 0.12 } } }}
				className="flex w-full max-w-[30rem] flex-col gap-10"
			>
				<section className="flex flex-col gap-4">
					<p className="eyebrow">What arrives</p>
					<div className="flex flex-col">
						{NOISE_ROWS.map((row) => (
							<motion.div key={row.id} variants={ROW} style={{ marginTop: row.gap }}>
								<div className="flex items-center gap-2.5" style={{ opacity: row.opacity }}>
									<span className="h-[3px] rounded-full bg-fg" style={{ width: row.title }} />
									<span className="h-[3px] rounded-full bg-fg" style={{ width: row.meta }} />
								</div>
							</motion.div>
						))}
					</div>
				</section>

				<section className="flex flex-col gap-4">
					<p className="eyebrow">What reaches you</p>
					<div className="flex flex-col gap-3.5">
						{SIFTED.map((item) => (
							<motion.div
								key={item.id}
								variants={ROW}
								className={`flex items-baseline gap-3 border-l-2 pl-3.5 ${PRIORITY_EDGE[item.priority]}`}
							>
								<p className="text-[13px] text-fg">{item.kind}</p>
								<p className="truncate font-mono text-[11px] text-fg-muted">{item.target}</p>
								<p className="ml-auto shrink-0 font-mono text-[11px] text-fg-muted">{item.when}</p>
							</motion.div>
						))}
					</div>
				</section>

				<motion.p variants={ROW} className="max-w-[38ch] text-[13px] leading-relaxed text-fg-muted">
					Most of what GitLab emails you is not about you. Sift shows you the part that is.
				</motion.p>
			</motion.div>
		</aside>
	);
}

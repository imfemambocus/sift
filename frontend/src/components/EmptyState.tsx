import type { ReactNode } from "react";
import { SiftMark } from "./SiftMark";

type EmptyStateProps = {
	readonly title: string;
	readonly description: string;
	readonly action?: ReactNode;
};

export function EmptyState({ title, description, action }: EmptyStateProps) {
	return (
		<div className="flex flex-col items-start gap-4 rounded-panel border border-dashed border-border px-8 py-14">
			<SiftMark className="size-6 text-fg-muted/50" />
			<div className="flex flex-col gap-1.5">
				<h2 className="text-[15px] font-medium tracking-[-0.01em] text-fg">{title}</h2>
				<p className="max-w-[46ch] text-[13px] leading-relaxed text-fg-muted">{description}</p>
			</div>
			{action}
		</div>
	);
}

import { useId } from "react";

type SiftMarkProps = {
	readonly className?: string;
	/** Omit where the mark sits next to the name already, so it is not announced twice. */
	readonly label?: string;
};

/*
 * three strokes of decreasing width: a lot arrives, less gets through, a little reaches you.
 * the same shape is the favicon.
 */
export function SiftMark({ className, label }: SiftMarkProps) {
	const titleId = useId();
	const decorative = label === undefined;

	return (
		<svg
			viewBox="0 0 32 32"
			className={className}
			fill="none"
			aria-hidden={decorative || undefined}
			aria-labelledby={decorative ? undefined : titleId}
		>
			{!decorative && <title id={titleId}>{label}</title>}
			<g stroke="currentColor" strokeWidth="3.2" strokeLinecap="round">
				<line x1="5" y1="9" x2="27" y2="9" />
				<line x1="9.5" y1="16" x2="22.5" y2="16" />
				<line x1="14" y1="23" x2="18" y2="23" />
			</g>
		</svg>
	);
}

import { SiftMark } from "./SiftMark";

export function Splash() {
	return (
		<div className="flex min-h-dvh items-center justify-center">
			<SiftMark className="size-6 text-fg-muted/40" label="Loading Sift" />
		</div>
	);
}

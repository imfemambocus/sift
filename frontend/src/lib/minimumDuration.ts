import { useEffect, useRef, useState } from "react";

const DEFAULT_MS = 450;

/**
 * True while {@link active}, then for long enough after it clears that the thing it gates was on
 * screen for at least {@link ms}.
 *
 * A skeleton that appears for 40ms and vanishes reads as a glitch rather than as loading, and against
 * a local backend that is most loads. Holding it briefly is the difference between the list arriving
 * and the page flickering.
 */
export function useMinimumDuration(active: boolean, ms = DEFAULT_MS): boolean {
	const [held, setHeld] = useState(active);
	const shownAt = useRef(0);

	useEffect(() => {
		if (active) {
			shownAt.current = Date.now();
			setHeld(true);
			return undefined;
		}

		const remaining = ms - (Date.now() - shownAt.current);
		if (remaining <= 0) {
			setHeld(false);
			return undefined;
		}

		const timer = window.setTimeout(() => setHeld(false), remaining);
		return () => window.clearTimeout(timer);
	}, [active, ms]);

	return held;
}

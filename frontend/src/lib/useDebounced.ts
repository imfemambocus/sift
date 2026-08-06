import { useEffect, useState } from "react";

/**
 * Holds a value back until it stops changing.
 *
 * <p>The search runs in the database now, so a query that became a query key on every keystroke
 * would be six requests for the word "review". This makes it one.
 */
export function useDebounced<T>(value: T, delay: number): T {
	const [settled, setSettled] = useState(value);

	useEffect(() => {
		const timer = setTimeout(() => setSettled(value), delay);
		return () => clearTimeout(timer);
	}, [value, delay]);

	return settled;
}

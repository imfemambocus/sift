const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7;

/** Compact enough to sit at the end of a row without competing with the title. */
export function shortAgo(iso: string, now: number = Date.now()): string {
	const elapsed = now - new Date(iso).getTime();

	if (elapsed < MINUTE) {
		return "now";
	}
	if (elapsed < HOUR) {
		return `${Math.floor(elapsed / MINUTE)}m`;
	}
	if (elapsed < DAY) {
		return `${Math.floor(elapsed / HOUR)}h`;
	}

	const days = Math.floor(elapsed / DAY);
	if (days < WEEK) {
		return `${days}d`;
	}
	return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

function startOfDay(date: Date): number {
	const copy = new Date(date);
	copy.setHours(0, 0, 0, 0);
	return copy.getTime();
}

/** Heading a row belongs under. Calendar days, not elapsed hours, which is how people read a list. */
export function dayGroup(iso: string, now: Date = new Date()): string {
	const date = new Date(iso);
	const daysApart = Math.round((startOfDay(now) - startOfDay(date)) / DAY);

	if (daysApart <= 0) {
		return "Today";
	}
	if (daysApart === 1) {
		return "Yesterday";
	}
	if (daysApart < WEEK) {
		return date.toLocaleDateString(undefined, { weekday: "long" });
	}
	return date.toLocaleDateString(undefined, { day: "numeric", month: "long" });
}

/** Prose form. `shortAgo` returns "now", and "now ago" is not a phrase. */
export function agoPhrase(iso: string, now: number = Date.now()): string {
	const short = shortAgo(iso, now);
	return short === "now" ? "just now" : `${short} ago`;
}

export function fullTimestamp(iso: string): string {
	return new Date(iso).toLocaleString();
}

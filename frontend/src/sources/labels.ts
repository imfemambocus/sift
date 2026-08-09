import { GitBranch, Mail } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { agoPhrase } from "../lib/time";
import type { SourceStatus } from "./sources";

const NAMES: Record<string, string> = {
	gitlab: "GitLab",
	gmail: "Gmail",
	outlook: "Outlook",
};

const PATHS: Record<string, string> = {
	gitlab: "/gitlab",
	gmail: "/gmail",
	outlook: "/outlook",
};

const ICONS: Record<string, LucideIcon> = {
	gitlab: GitBranch,
	gmail: Mail,
	outlook: Mail,
};

/** One line each, for the card that offers a source nobody has connected yet. */
const OFFERS: Record<string, string> = {
	gitlab: "To-dos, review requests, and replies on what you are part of.",
	gmail: "Every message, in the same list, with a search that actually finds things.",
	outlook: "Every message, in the same list, with a search that actually finds things.",
};

export function sourceName(source: string): string {
	return NAMES[source] ?? source.charAt(0).toUpperCase() + source.slice(1);
}

/** Falls back to Home, so a source with no tab yet still links somewhere sensible. */
export function sourcePath(source: string): string {
	return PATHS[source] ?? "/";
}

/** Falls back to the generic dot, so an unknown source still draws rather than crashing the rail. */
export function sourceIcon(source: string): LucideIcon {
	return ICONS[source] ?? GitBranch;
}

export function sourceOffer(source: string): string {
	return OFFERS[source] ?? `Bring ${sourceName(source)} into the same list.`;
}

/**
 * Whether Sift is still filling this source in. A read in flight is one way; a history it has not
 * reached the beginning of is the other, and a mailbox takes many sweeps to walk back through. In
 * between those sweeps nothing is in flight, but the source is no nearer finished than it is
 * mid-read, so both count as one state.
 */
export function isReading(source: SourceStatus): boolean {
	if (source.syncing) {
		return true;
	}
	// a source whose last read failed is not mid-anything: it waits for somebody, and says so
	return source.status === "OK" && !source.historyComplete;
}

/**
 * Where a source's reading is up to, in one phrase. One copy, because the feed page, the Home card
 * and the settings card all answer it and three wordings for one fact is how they come to disagree.
 *
 * <p>"Synced" rather than "read": an item is read or unread, and one word cannot mean both.
 *
 * @param asked a read this page has asked for and is still waiting on. The server is what says a
 *     source is being read, and it is only asked again every so often, so without this the words
 *     stay on "Synced 2m ago" for the whole of a read somebody just pressed.
 */
export function syncPhrase(source: SourceStatus, asked = false): string {
	if (asked || isReading(source)) {
		return "Syncing now";
	}
	if (source.lastSyncAt === null) {
		return "Not synced yet";
	}
	return `Synced ${agoPhrase(source.lastSyncAt)}`;
}

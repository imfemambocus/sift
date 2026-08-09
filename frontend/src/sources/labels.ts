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
 * Where a source's reading is up to, in one phrase. One copy, because the feed page, the Home card
 * and the settings card all answer it and three wordings for one fact is how they come to disagree.
 *
 * <p>"Synced" rather than "read": an item is read or unread, and one word cannot mean both.
 */
export function syncPhrase(source: SourceStatus): string {
	if (source.syncing) {
		return "Syncing now";
	}
	if (source.lastSyncAt === null) {
		return "Not synced yet";
	}
	return `Synced ${agoPhrase(source.lastSyncAt)}`;
}

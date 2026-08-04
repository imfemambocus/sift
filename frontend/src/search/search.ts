import uFuzzy from "@leeoniya/ufuzzy";
import type { FeedItem } from "../feed/feed";
import { kindLabel } from "../feed/kinds";

/*
 * One search over everything, however you half remember it. This is one of the two complaints the app
 * started from: Outlook held the mail but could not find anything in it.
 *
 * intraIns allows one typo inside a term, and terms may be typed in any order up to four of them, so
 * "color chart" finds "Chart V2: Line chart color encoding".
 */
const uf = new uFuzzy({ intraIns: 1 });
const PERMUTE_UP_TO = 4;

const PREFIX = /^(is|project|from):(.+)$/i;

type Filter = { readonly key: string; readonly value: string };

export function isSearching(query: string): boolean {
	return query.trim() !== "";
}

export function searchFeed(items: readonly FeedItem[], query: string): readonly FeedItem[] {
	const { filters, text } = parse(query);
	const scoped = items.filter((item) => filters.every((filter) => passes(item, filter)));

	if (text === "") {
		return scoped;
	}

	const [idxs] = uf.search(scoped.map(haystackFor), text, PERMUTE_UP_TO);
	if (idxs === null) {
		return [];
	}

	/*
	 * the ranked order uFuzzy also returns is deliberately thrown away. results render through the
	 * ordinary FeedList, which groups by day, and a relevance order would repeat the same day heading
	 * down the page. matching, not ranking, is what this is here for.
	 */
	return [...idxs]
		.sort((a, b) => a - b)
		.map((idx) => scoped[idx])
		.filter((item) => item !== undefined);
}

/** Every token has to match, so `is:mr is:unread` narrows and `project:a project:b` finds nothing. */
function parse(query: string): { readonly filters: readonly Filter[]; readonly text: string } {
	const filters: Filter[] = [];
	const words: string[] = [];

	for (const word of query.trim().split(/\s+/)) {
		const match = PREFIX.exec(word);
		if (match === null) {
			words.push(word);
			continue;
		}
		const [, key = "", value = ""] = match;
		filters.push({ key: key.toLowerCase(), value: value.toLowerCase() });
	}
	return { filters, text: words.join(" ") };
}

function passes(item: FeedItem, filter: Filter): boolean {
	if (filter.key === "project") {
		return contains(item.contextLabel, filter.value);
	}
	if (filter.key === "from") {
		return contains(item.actorName, filter.value);
	}
	return isKind(item, filter.value);
}

function isKind(item: FeedItem, value: string): boolean {
	switch (value) {
		// the url is what reliably says which of the two something is, across every kind of row
		case "mr":
			return item.url.includes("/merge_requests/");
		case "issue":
			return item.url.includes("/issues/");
		case "unread":
			return !item.read;
		case "read":
			return item.read;
		// anything else falls through to the source's own token, so is:merged and is:thread work
		default:
			return item.kind.toLowerCase().includes(value);
	}
}

function contains(field: string | null, value: string): boolean {
	return field !== null && field.toLowerCase().includes(value);
}

function haystackFor(item: FeedItem): string {
	return [item.title, item.body, item.contextLabel, item.actorName, kindLabel(item.kind)]
		.filter((part) => part !== null)
		.join(" ");
}

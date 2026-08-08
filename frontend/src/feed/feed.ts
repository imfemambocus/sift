import type { InfiniteData } from "@tanstack/react-query";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { request } from "../lib/api";
import type { FeedFilter, FeedOrder } from "./view";

/*
 * source and kind are parsed as plain strings rather than enums on purpose. this app's job is to
 * not hide things, and a strict enum would make one unfamiliar value from a newer backend fail the
 * whole response and show an empty feed. unknown values fall back when they are rendered.
 */
const feedItemSchema = z.object({
	id: z.string(),
	source: z.string(),
	kind: z.string(),
	title: z.string(),
	body: z.string().nullable(),
	actorName: z.string().nullable(),
	actorAvatarUrl: z.string().nullable(),
	contextLabel: z.string().nullable(),
	contextUrl: z.string().nullable(),
	url: z.string(),
	/** Opaque: rows sharing one are about the same thing. See grouping.ts. */
	groupKey: z.string(),
	createdAt: z.string(),
	activityAt: z.string(),
	read: z.boolean(),
	/** The source stopped reporting it: done, merged or closed. It stays in the feed, on purpose. */
	resolved: z.boolean(),
});

const feedPageSchema = z.object({
	items: z.array(feedItemSchema),
	/** Null is the last page, so a "Show more" is never a button that would do nothing. */
	nextCursor: z.string().nullable(),
});

const feedSummarySchema = z.object({
	source: z.string(),
	total: z.number(),
	unread: z.number(),
	/** Only what the source still reports. A merged merge request is history, not a thing waiting. */
	waiting: z.number(),
	waitingUnread: z.number(),
	waitingByKind: z.record(z.string(), z.number()),
});

const summarySchema = z.array(feedSummarySchema);

export type FeedItem = z.infer<typeof feedItemSchema>;
export type FeedPage = z.infer<typeof feedPageSchema>;
export type FeedSummary = z.infer<typeof feedSummarySchema>;

export const FEED_KEY = "feed";
export const SUMMARY_KEY = "feed-summary";

const NOTHING: FeedSummary = {
	source: "",
	total: 0,
	unread: 0,
	waiting: 0,
	waitingUnread: 0,
	waitingByKind: {},
};

/** What narrows one view of the feed. Every field of it is part of the query key. */
export type FeedView = {
	/** Absent is every source, which is what the search wants. */
	readonly source?: string;
	readonly filter: FeedFilter;
	readonly order: FeedOrder;
	readonly query?: string;
};

function pathFor(view: FeedView, cursor: string | null): string {
	const params = new URLSearchParams({ filter: view.filter, order: view.order });
	if (view.source !== undefined) {
		params.set("source", view.source);
	}
	if (view.query !== undefined && view.query.trim() !== "") {
		params.set("q", view.query.trim());
	}
	if (cursor !== null) {
		params.set("cursor", cursor);
	}
	return `/api/feed?${params.toString()}`;
}

/**
 * One page of the feed at a time, as whole groups.
 *
 * <p>The server narrows, orders, searches and pages, so neither this request nor the poll behind it
 * grows with the history. Anything that needs a number over more than the loaded pages reads
 * {@link useFeedSummary} instead.
 */
export function useFeedPages(view: FeedView, enabled = true) {
	return useInfiniteQuery({
		queryKey: [FEED_KEY, view.source ?? "all", view.filter, view.order, view.query?.trim() ?? ""],
		queryFn: async ({ pageParam }) => feedPageSchema.parse(await request<unknown>(pathFor(view, pageParam))),
		initialPageParam: null as string | null,
		getNextPageParam: (last: FeedPage) => last.nextCursor,
		// off while a search query is still being typed, so a half-word never asks for the whole feed
		enabled,
		/*
		 * tanstack's default of pausing while the tab is in the background is wanted here: nobody
		 * reads a list they are not looking at, and refetching every loaded page in a hidden tab is
		 * the expensive half of the app. The summary overrides it, since the tab badge is read
		 * precisely when this list is not.
		 */
		refetchInterval: 30_000,
	});
}

/** Every page loaded so far, flattened. Groups survive the seam, since `intoGroups` merges by key. */
export function itemsOf(data: InfiniteData<FeedPage> | undefined): readonly FeedItem[] {
	return data === undefined ? [] : data.pages.flatMap((page) => page.items);
}

/**
 * The counts behind every number the app shows without showing the rows: the All / Unread / Read
 * control, Home's cards, and the count on the tab. One request for every source at once.
 */
export function useFeedSummary() {
	return useQuery({
		queryKey: [SUMMARY_KEY],
		queryFn: async () => summarySchema.parse(await request<unknown>("/api/feed/summary")),
		refetchInterval: 30_000,
		/*
		 * TanStack pauses an interval whenever the window loses focus, and this one must not: the
		 * count on the tab is read while somebody is looking at something else, so a paused poll
		 * would make it true only once they looked, which is when it stops being needed. One small
		 * request, so a background poll is cheap. A browser throttles a timer in a hidden tab, so
		 * treat 30s as about a minute there.
		 */
		refetchIntervalInBackground: true,
	});
}

/** Zeros rather than undefined for a source with no rows yet, so a card can render before a sync. */
export function summaryFor(summary: readonly FeedSummary[] | undefined, source: string): FeedSummary {
	return summary?.find((entry) => entry.source === source) ?? { ...NOTHING, source };
}

export function totalUnread(summary: readonly FeedSummary[] | undefined): number {
	return (summary ?? []).reduce((sum, entry) => sum + entry.unread, 0);
}

type CachedFeeds = readonly (readonly [readonly unknown[], InfiniteData<FeedPage> | undefined])[];

/**
 * Writes `read` onto every cached item the predicate picks, and hands back what was there so an error
 * can put it all back. Shared by the two read mutations, which differ only in what they select.
 */
async function optimisticRead(
	queryClient: ReturnType<typeof useQueryClient>,
	picks: (item: FeedItem) => boolean,
	read: boolean,
): Promise<CachedFeeds> {
	// by prefix, so every narrowed feed on screen is written at once and none of them disagree
	await queryClient.cancelQueries({ queryKey: [FEED_KEY] });
	const previous = queryClient.getQueriesData<InfiniteData<FeedPage>>({ queryKey: [FEED_KEY] });

	queryClient.setQueriesData<InfiniteData<FeedPage>>({ queryKey: [FEED_KEY] }, (feed) =>
		feed === undefined ? feed : {
			...feed,
			pages: feed.pages.map((page) => ({
				...page,
				items: page.items.map((item) => (picks(item) ? { ...item, read } : item)),
			})),
		},
	);
	return previous;
}

function rollback(queryClient: ReturnType<typeof useQueryClient>, previous: CachedFeeds | undefined) {
	for (const [key, feed] of previous ?? []) {
		queryClient.setQueryData(key, feed);
	}
}

/**
 * Anything that changes the rows changes the counts as well, and the two are separate requests, so
 * they are refreshed together. Every caller has to use this rather than the feed key alone: a page
 * whose list moved while its All / Unread / Read numbers did not is the visible failure.
 *
 * <p>The counts are refetched rather than written optimistically. The row greying out under the
 * cursor is what would look broken if it waited for a round trip; a number in the corner catching up
 * one beat later does not, and working the delta out per source from several cached pages would be a
 * second way to get the counts wrong.
 */
export async function invalidateFeed(queryClient: ReturnType<typeof useQueryClient>) {
	await Promise.all([
		queryClient.invalidateQueries({ queryKey: [FEED_KEY] }),
		queryClient.invalidateQueries({ queryKey: [SUMMARY_KEY] }),
	]);
}

/** Several ids at once, so clearing a whole group is one gesture and one cache write. */
type SetReadInput = { readonly ids: readonly string[]; readonly read: boolean };

/**
 * Optimistic, because the row is usually clicked on the way out to GitLab and waiting for a
 * round trip to grey it out would look broken.
 */
export function useSetRead() {
	const queryClient = useQueryClient();

	return useMutation({
		mutationFn: async ({ ids, read }: SetReadInput) => {
			await Promise.all(
				ids.map((id) => request<null>(`/api/feed/${id}`, { method: "PATCH", body: JSON.stringify({ read }) })),
			);
		},

		onMutate: async ({ ids, read }) => {
			const changing = new Set(ids);
			return { previous: await optimisticRead(queryClient, (item) => changing.has(item.id), read) };
		},

		onError: (_error, _input, context) => rollback(queryClient, context?.previous),

		onSettled: () => invalidateFeed(queryClient),
	});
}

/**
 * Clears a whole source in one request. The client cannot do this by patching each id: a full feed
 * would be hundreds of requests, which is the reason the endpoint exists at all.
 */
export function useMarkAllRead(source?: string) {
	const queryClient = useQueryClient();
	const path = source === undefined
		? "/api/feed/read-all"
		: `/api/feed/read-all?source=${encodeURIComponent(source)}`;

	return useMutation({
		mutationFn: () => request<null>(path, { method: "POST" }),

		// the server clears the source, not the current filter, so the optimistic write matches it
		onMutate: async () => ({
			previous: await optimisticRead(
				queryClient,
				(item) => source === undefined || item.source === source,
				true,
			),
		}),

		onError: (_error, _input, context) => rollback(queryClient, context?.previous),

		onSettled: () => invalidateFeed(queryClient),
	});
}

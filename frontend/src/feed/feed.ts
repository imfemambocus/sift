import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { request } from "../lib/api";

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
});

const feedSchema = z.array(feedItemSchema);

export type FeedItem = z.infer<typeof feedItemSchema>;

export const FEED_KEY = "feed";

/*
 * One query for the whole app, every source at once. A source tab narrows it in the browser rather
 * than asking for `?source=`, because the search field, the tab badge and Home all want everything:
 * a second per-source query would only mean polling the same endpoint twice. The backend's filter
 * stays, for when the corpus is big enough that shipping all of it stops being sensible.
 */
export function useFeed() {
	return useQuery({
		queryKey: [FEED_KEY],
		queryFn: async () => feedSchema.parse(await request<unknown>("/api/feed")),
		// tanstack pauses interval refetching while the tab is in the background, so this is only
		// every 30s when someone is actually looking at it
		refetchInterval: 30_000,
	});
}

export function bySource(items: readonly FeedItem[], source: string): readonly FeedItem[] {
	return items.filter((item) => item.source === source);
}

export function unreadCount(items: readonly FeedItem[]): number {
	return items.filter((item) => !item.read).length;
}

type CachedFeeds = readonly (readonly [readonly unknown[], FeedItem[] | undefined])[];

/**
 * Writes `read` onto every cached item the predicate picks, and hands back what was there so an error
 * can put it all back. Shared by the two read mutations, which differ only in what they select.
 */
async function optimisticRead(
	queryClient: ReturnType<typeof useQueryClient>,
	picks: (item: FeedItem) => boolean,
	read: boolean,
): Promise<CachedFeeds> {
	// by prefix, so it still holds if a narrowed feed query is ever added beside the one
	await queryClient.cancelQueries({ queryKey: [FEED_KEY] });
	const previous = queryClient.getQueriesData<FeedItem[]>({ queryKey: [FEED_KEY] });

	queryClient.setQueriesData<FeedItem[]>({ queryKey: [FEED_KEY] }, (feed) =>
		feed?.map((item) => (picks(item) ? { ...item, read } : item)),
	);
	return previous;
}

function rollback(queryClient: ReturnType<typeof useQueryClient>, previous: CachedFeeds | undefined) {
	for (const [key, feed] of previous ?? []) {
		queryClient.setQueryData(key, feed);
	}
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

		onSettled: async () => {
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
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

		onSettled: async () => {
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
	});
}

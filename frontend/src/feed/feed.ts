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
	createdAt: z.string(),
	activityAt: z.string(),
	read: z.boolean(),
});

const feedSchema = z.array(feedItemSchema);

export type FeedItem = z.infer<typeof feedItemSchema>;

export const FEED_KEY = "feed";

export function useFeed(source?: string) {
	return useQuery({
		queryKey: [FEED_KEY, source ?? "all"],
		queryFn: async () => {
			const path = source === undefined ? "/api/feed" : `/api/feed?source=${encodeURIComponent(source)}`;
			return feedSchema.parse(await request<unknown>(path));
		},
		// tanstack pauses interval refetching while the tab is in the background, so this is only
		// every 30s when someone is actually looking at it
		refetchInterval: 30_000,
	});
}

export function unreadCount(items: readonly FeedItem[]): number {
	return items.filter((item) => !item.read).length;
}

type SetReadInput = { readonly id: string; readonly read: boolean };

/**
 * Optimistic, because the row is usually clicked on the way out to GitLab and waiting for a
 * round trip to grey it out would look broken.
 */
export function useSetRead() {
	const queryClient = useQueryClient();

	return useMutation({
		mutationFn: ({ id, read }: SetReadInput) =>
			request<null>(`/api/feed/${id}`, { method: "PATCH", body: JSON.stringify({ read }) }),

		onMutate: async ({ id, read }) => {
			// every cached feed holds the same items, so home and a source tab stay in step
			await queryClient.cancelQueries({ queryKey: [FEED_KEY] });
			const previous = queryClient.getQueriesData<FeedItem[]>({ queryKey: [FEED_KEY] });

			queryClient.setQueriesData<FeedItem[]>({ queryKey: [FEED_KEY] }, (feed) =>
				feed?.map((item) => (item.id === id ? { ...item, read } : item)),
			);
			return { previous };
		},

		onError: (_error, _input, context) => {
			for (const [key, feed] of context?.previous ?? []) {
				queryClient.setQueryData(key, feed);
			}
		},

		onSettled: async () => {
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
	});
}

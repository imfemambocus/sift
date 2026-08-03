import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { request } from "../lib/api";

/*
 * priority and source are parsed as plain strings rather than enums on purpose. this app's job is
 * to not hide things, and a strict enum would make one unfamiliar value from a newer backend fail
 * the whole response and show an empty feed. unknown values fall back when they are rendered.
 */
const feedItemSchema = z.object({
	id: z.string(),
	source: z.string(),
	kind: z.string(),
	priority: z.string(),
	title: z.string(),
	body: z.string().nullable(),
	actorName: z.string().nullable(),
	actorAvatarUrl: z.string().nullable(),
	contextLabel: z.string().nullable(),
	contextUrl: z.string().nullable(),
	url: z.string(),
	createdAt: z.string(),
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

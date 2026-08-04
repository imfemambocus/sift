import { useIsMutating, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { FEED_KEY } from "../feed/feed";
import { request } from "../lib/api";

// status is a plain string for the same reason a feed item's kind is: a value this build has not
// seen must not fail the whole response and leave the settings page blank
const sourceStatusSchema = z.object({
	source: z.string(),
	instanceUrl: z.string(),
	credentialType: z.string(),
	status: z.string(),
	lastError: z.string().nullable(),
	lastSyncAt: z.string().nullable(),
	itemCount: z.number(),
	account: z
		.object({
			username: z.string().nullable(),
			displayName: z.string().nullable(),
			avatarUrl: z.string().nullable(),
			webUrl: z.string().nullable(),
		})
		.nullable(),
});

const sourcesSchema = z.array(sourceStatusSchema);

export type SourceStatus = z.infer<typeof sourceStatusSchema>;

const SOURCES_KEY = ["sources"];

export function useSources() {
	return useQuery({
		queryKey: SOURCES_KEY,
		queryFn: async () => sourcesSchema.parse(await request<unknown>("/api/sources")),
	});
}

export function useSource(source: string) {
	const query = useSources();
	return { ...query, data: query.data?.find((entry) => entry.source === source) };
}

export function useConnectSource(source: string) {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: async (input: { instanceUrl: string; token: string }) => {
			const payload = await request<unknown>(`/api/sources/${source}/connect`, {
				method: "POST",
				body: JSON.stringify(input),
			});
			return sourceStatusSchema.parse(payload);
		},
		// connecting runs a first sync server-side, so the feed has something new to show
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: SOURCES_KEY });
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
	});
}

const SYNC_KEY = "sync-source";

/** Reads the source now rather than waiting for the sweep. */
export function useSyncSource(source: string) {
	const queryClient = useQueryClient();
	return useMutation({
		// keyed so a feed page can skeleton itself while this runs, wherever it was triggered from
		mutationKey: [SYNC_KEY, source],
		mutationFn: async () => {
			const payload = await request<unknown>(`/api/sources/${source}/sync`, { method: "POST" });
			return sourceStatusSchema.parse(payload);
		},
		// awaited, so the mutation stays pending until the refetched feed has actually landed
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: SOURCES_KEY });
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
	});
}

/**
 * Whether a refresh someone asked for is in flight, for one source or for any of them.
 *
 * <p>Read off the mutation cache rather than passed down, because the button that starts it is a
 * sibling of the list in one place and a whole page away in the other.
 */
export function useIsSyncing(source?: string): boolean {
	const mutationKey = source === undefined ? [SYNC_KEY] : [SYNC_KEY, source];
	return useIsMutating({ mutationKey }) > 0;
}

export function useDisconnectSource(source: string) {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: () => request<null>(`/api/sources/${source}`, { method: "DELETE" }),
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: SOURCES_KEY });
			await queryClient.invalidateQueries({ queryKey: [FEED_KEY] });
		},
	});
}

import { useIsMutating, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { invalidateFeed } from "../feed/feed";
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
	/** Which account at the source. Null until a sweep has learned it. */
	account: z.string().nullable(),
	/** False while the source is still reading its older history, which can take hours. */
	historyComplete: z.boolean(),
});

const sourcesSchema = z.array(sourceStatusSchema);

export type SourceStatus = z.infer<typeof sourceStatusSchema>;

const SOURCES_KEY = ["sources"];

const connectorSchema = z.object({
	source: z.string(),
	connected: z.boolean(),
	// whether this deployment registered an application, which decides what the offer can say
	configured: z.boolean(),
	target: z.string().nullable(),
});

const connectorsSchema = z.array(connectorSchema);

export type Connector = z.infer<typeof connectorSchema>;

const authorizationSchema = z.object({ authorizeUrl: z.string() });

const CONNECTORS_KEY = ["connectors"];

/**
 * Every source the app can connect, connected or not. Home draws a card for each: a summary for the
 * ones that are, an invitation for the ones that are not.
 *
 * <p>Not `staleTime: Infinity`, unlike the availability query it replaces: `connected` changes the
 * moment somebody authorizes or disconnects one.
 */
export function useConnectors() {
	return useQuery({
		queryKey: CONNECTORS_KEY,
		queryFn: async () => connectorsSchema.parse(await request<unknown>("/api/sources/connectors")),
	});
}

export function useConnector(source: string) {
	const query = useConnectors();
	return { ...query, data: query.data?.find((entry) => entry.source === source) };
}

/**
 * Starts the authorization, then hands the browser to the provider. The exchange happens
 * server-side, so no token ever reaches this code.
 */
export function useStartOAuth(source: string) {
	return useMutation({
		mutationFn: async () => {
			const payload = await request<unknown>(`/api/sources/${source}/oauth/start`, { method: "POST" });
			const { authorizeUrl } = authorizationSchema.parse(payload);
			window.location.assign(authorizeUrl);
		},
	});
}

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
			await invalidateFeed(queryClient);
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
			// this one flips `connected`, so the rail drops its icon and Home puts the offer back
			await queryClient.invalidateQueries({ queryKey: CONNECTORS_KEY });
			await invalidateFeed(queryClient);
		},
	});
}

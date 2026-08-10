import { useIsMutating, useMutation, useMutationState, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
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
	/** How far back that reading has reached. Null for a source with no history of its own. */
	historyFrom: z.string().nullable(),
	/** True when the last few reads reached nothing older, so the walk back is getting nowhere. */
	historyStalled: z.boolean(),
	/** True when this source can be asked to read its history again from the beginning. */
	canReread: z.boolean(),
	/** True while a read of this source is running on the server, whoever asked for it. */
	syncing: z.boolean(),
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
		/*
		 * a read runs on the server and nobody here started it, so polling is the only way anything on
		 * this side learns that one is running or that it has finished. faster while one is, so the
		 * indicator clears about when the reading really stops rather than up to a quarter of a minute
		 * later. it stops when the window does, unlike the unread count: nobody reads a status they are
		 * not looking at.
		 */
		refetchInterval: (query) => (query.state.data?.some((entry) => entry.syncing) ? 2_000 : 15_000),
	});
}

/**
 * Refreshes the feed when a read that was running on the server finishes.
 *
 * <p>The rows land in the database with nothing on this side knowing, and a connection that has just
 * read a mailbox would otherwise sit empty until the list's own poll came round. Mounted once, in the
 * frame, so it covers whichever page is open.
 */
export function useRefreshWhenSynced() {
	const { data } = useSources();
	const queryClient = useQueryClient();
	const syncing = data?.some((entry) => entry.syncing) ?? false;
	const wasSyncing = useRef(syncing);

	useEffect(() => {
		if (wasSyncing.current && !syncing) {
			invalidateFeed(queryClient);
		}
		wasSyncing.current = syncing;
	}, [syncing, queryClient]);
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

/**
 * Why the last refresh of this source failed, for whoever wants to say so. Off the mutation cache
 * for the same reason: the button owns the request, and the message belongs on its own line under
 * whatever the button sits in.
 */
export function useSyncError(source: string): unknown {
	const errors = useMutationState({
		filters: { mutationKey: [SYNC_KEY, source] },
		select: (mutation) => mutation.state.error,
	});
	// the newest, since a press while a failed one is still cached would otherwise show the old reason
	return errors.at(-1) ?? null;
}

const REREAD_KEY = "reread-source";

/**
 * Asks the source to read its history again from the beginning.
 *
 * <p>Its own key rather than the sync one, and it invalidates no feed page: the rows stay where they
 * are and fill back in over the following reads, so a skeleton here would take away a list that is
 * not going anywhere. `useRefreshWhenSynced` in the frame picks the new rows up as each read ends.
 */
export function useRereadSource(source: string) {
	const queryClient = useQueryClient();
	return useMutation({
		mutationKey: [REREAD_KEY, source],
		mutationFn: async () => {
			const payload = await request<unknown>(`/api/sources/${source}/reread`, { method: "POST" });
			return sourceStatusSchema.parse(payload);
		},
		onSuccess: async () => {
			await queryClient.invalidateQueries({ queryKey: SOURCES_KEY });
		},
	});
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

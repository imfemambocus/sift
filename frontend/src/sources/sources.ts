import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { FEED_KEY } from "../feed/feed";
import { request } from "../lib/api";

// status is a plain string for the same reason feed priority is: a value this build has not seen
// must not fail the whole response and leave the settings page blank
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

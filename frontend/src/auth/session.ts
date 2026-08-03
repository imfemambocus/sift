import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { ApiError, request } from "../lib/api";

const sessionSchema = z.object({
	id: z.string(),
	email: z.string(),
	displayName: z.string(),
});

export type Session = z.infer<typeof sessionSchema>;

const SESSION_KEY = ["session"];

async function fetchSession(): Promise<Session | null> {
	try {
		return sessionSchema.parse(await request<unknown>("/api/auth/me"));
	} catch (error) {
		// not signed in is an expected answer here, not a failure
		if (error instanceof ApiError && error.status === 401) {
			return null;
		}
		throw error;
	}
}

export function useSession() {
	return useQuery({
		queryKey: SESSION_KEY,
		queryFn: fetchSession,
		retry: false,
		staleTime: 30_000,
	});
}

async function signIn(credentials: { email: string; password: string }): Promise<Session> {
	const payload = await request<unknown>("/api/auth/login", {
		method: "POST",
		body: JSON.stringify(credentials),
	});
	return sessionSchema.parse(payload);
}

export function useSignIn() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: signIn,
		onSuccess: (session) => queryClient.setQueryData(SESSION_KEY, session),
	});
}

export function useCreateAccount() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: async (input: { email: string; displayName: string; password: string }) => {
			await request<unknown>("/api/auth/register", { method: "POST", body: JSON.stringify(input) });
			// registering does not authenticate, so the new account is signed straight in
			return signIn({ email: input.email, password: input.password });
		},
		onSuccess: (session) => queryClient.setQueryData(SESSION_KEY, session),
	});
}

export function useSignOut() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: () => request<null>("/api/auth/logout", { method: "POST" }),
		onSuccess: () => {
			queryClient.clear();
			queryClient.setQueryData(SESSION_KEY, null);
		},
	});
}

export function errorMessage(error: unknown): string | null {
	if (error instanceof ApiError) {
		return error.message;
	}
	if (error instanceof Error) {
		return "Could not reach Sift. Check that the backend is running.";
	}
	return null;
}

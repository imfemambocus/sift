const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export class ApiError extends Error {
	readonly status: number;

	constructor(status: number, message: string) {
		super(message);
		this.name = "ApiError";
		this.status = status;
	}
}

function readCookie(name: string): string | null {
	const prefix = `${name}=`;
	const entry = document.cookie.split("; ").find((part) => part.startsWith(prefix));
	return entry === undefined ? null : decodeURIComponent(entry.slice(prefix.length));
}

/*
 * the token only arrives on a response, so a cold load that posts before it has read anything
 * would be rejected. one cheap public request seeds the cookie.
 */
async function ensureCsrfToken(): Promise<void> {
	if (readCookie(CSRF_COOKIE) !== null) {
		return;
	}
	await fetch("/actuator/health", { credentials: "same-origin" });
}

function detailFrom(payload: unknown): string | null {
	if (typeof payload !== "object" || payload === null) {
		return null;
	}
	const detail = (payload as { detail?: unknown }).detail;
	return typeof detail === "string" ? detail : null;
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
	const method = (init.method ?? "GET").toUpperCase();
	const headers = new Headers(init.headers);

	if (!SAFE_METHODS.has(method)) {
		await ensureCsrfToken();
		const token = readCookie(CSRF_COOKIE);
		if (token !== null) {
			headers.set(CSRF_HEADER, token);
		}
		if (init.body !== undefined && !headers.has("Content-Type")) {
			headers.set("Content-Type", "application/json");
		}
	}

	const response = await fetch(path, { ...init, method, headers, credentials: "same-origin" });

	const body = await response.text();
	const payload: unknown = body.length > 0 ? JSON.parse(body) : null;

	if (!response.ok) {
		throw new ApiError(response.status, detailFrom(payload) ?? "The request could not be completed.");
	}

	return payload as T;
}

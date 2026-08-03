const NAMES: Record<string, string> = {
	gitlab: "GitLab",
	outlook: "Outlook",
};

const PATHS: Record<string, string> = {
	gitlab: "/gitlab",
	outlook: "/outlook",
};

export function sourceName(source: string): string {
	return NAMES[source] ?? source.charAt(0).toUpperCase() + source.slice(1);
}

/** Falls back to Home, so a source with no tab yet still links somewhere sensible. */
export function sourcePath(source: string): string {
	return PATHS[source] ?? "/";
}

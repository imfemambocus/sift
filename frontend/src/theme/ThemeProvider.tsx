import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

export type ThemePreference = "system" | "light" | "dark";
type ResolvedTheme = "light" | "dark";

const STORAGE_KEY = "sift-theme";
const LIGHT_QUERY = "(prefers-color-scheme: light)";

function resolvePreference(preference: ThemePreference): ResolvedTheme {
	if (preference !== "system") {
		return preference;
	}
	return window.matchMedia(LIGHT_QUERY).matches ? "light" : "dark";
}

function readStoredPreference(): ThemePreference {
	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (stored === "light" || stored === "dark" || stored === "system") {
			return stored;
		}
	} catch {
		// private browsing can refuse storage; the default is good enough to carry on with
	}
	return "dark";
}

type ThemeContextValue = {
	readonly preference: ThemePreference;
	readonly resolved: ResolvedTheme;
	readonly setPreference: (next: ThemePreference) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { readonly children: ReactNode }) {
	const [preference, setPreference] = useState<ThemePreference>(readStoredPreference);
	const [resolved, setResolved] = useState<ResolvedTheme>(() => resolvePreference(readStoredPreference()));

	useEffect(() => {
		const apply = () => {
			const next = resolvePreference(preference);
			document.documentElement.dataset.theme = next;
			setResolved(next);
		};
		apply();

		try {
			window.localStorage.setItem(STORAGE_KEY, preference);
		} catch {
			// nothing to do: the theme still applies for this session
		}

		if (preference !== "system") {
			return;
		}

		const media = window.matchMedia(LIGHT_QUERY);
		media.addEventListener("change", apply);
		return () => media.removeEventListener("change", apply);
	}, [preference]);

	const value = useMemo(
		() => ({ preference, resolved, setPreference }),
		[preference, resolved],
	);

	return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
	const context = useContext(ThemeContext);
	if (context === null) {
		throw new Error("useTheme must be used inside a ThemeProvider");
	}
	return context;
}

export function useCycleTheme(): () => void {
	const { preference, setPreference } = useTheme();
	const next: Record<ThemePreference, ThemePreference> = {
		light: "dark",
		dark: "system",
		system: "light",
	};
	return useCallback(() => setPreference(next[preference]), [next, preference, setPreference]);
}

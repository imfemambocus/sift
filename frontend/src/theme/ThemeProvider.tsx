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

const SWITCHING_ATTRIBUTE = "data-theme-switching";
const FADE_PROPERTY = "--theme-fade";

/*
 * one timer for the one <html> element, so a second toggle mid-fade extends the flag rather than an
 * earlier removal cutting the newer fade short.
 */
let endOfFade: number | undefined;

/**
 * Fades the whole page to the new theme on one clock, by hanging {@code data-theme-switching} on
 * {@code <html>} for exactly as long as the fade lasts. The rule it turns on lives in {@code index.css};
 * it has to be imposed on everything for the length of the swap rather than left to each component,
 * since what components declare for their hover states is all different.
 *
 * <p>Both attributes are set in the same task on purpose: the browser computes one style change, from
 * the old colours to the new ones *with* the transition already declared, which is what starts it.
 */
function applyThemeWithFade(theme: ResolvedTheme) {
	const root = document.documentElement;
	if (root.dataset.theme === theme) {
		// first mount: the inline script in index.html already resolved it, so there is nothing to fade
		return;
	}

	root.setAttribute(SWITCHING_ATTRIBUTE, "");
	root.dataset.theme = theme;

	window.clearTimeout(endOfFade);
	endOfFade = window.setTimeout(() => root.removeAttribute(SWITCHING_ATTRIBUTE), fadeMilliseconds(root));
}

/** Read back rather than duplicated, so reduced motion's 0ms is honoured without a second check. */
function fadeMilliseconds(root: Element): number {
	const declared = getComputedStyle(root).getPropertyValue(FADE_PROPERTY).trim();
	const value = Number.parseFloat(declared);
	if (Number.isNaN(value)) {
		return 0;
	}
	return declared.endsWith("ms") ? value : value * 1000;
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
			applyThemeWithFade(next);
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

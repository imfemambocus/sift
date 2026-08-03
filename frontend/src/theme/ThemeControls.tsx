import { Monitor, Moon, Sun } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { RAIL_BUTTON, RAIL_IDLE, RailTooltip } from "../components/rail";
import type { ThemePreference } from "./ThemeProvider";
import { useCycleTheme, useTheme } from "./ThemeProvider";

const ICON: Record<ThemePreference, LucideIcon> = {
	light: Sun,
	dark: Moon,
	system: Monitor,
};

const LABEL: Record<ThemePreference, string> = {
	light: "Light",
	dark: "Dark",
	system: "Match system",
};

const ORDER: readonly ThemePreference[] = ["light", "dark", "system"];

/** Rail shortcut: one button that steps through the three preferences. */
export function ThemeCycleButton() {
	const { preference } = useTheme();
	const cycle = useCycleTheme();
	const Icon = ICON[preference];

	return (
		<button
			type="button"
			onClick={cycle}
			aria-label={`Theme: ${LABEL[preference].toLowerCase()}. Change it.`}
			className={`${RAIL_BUTTON} ${RAIL_IDLE}`}
		>
			<Icon size={17} strokeWidth={1.75} />
			<RailTooltip>{LABEL[preference]}</RailTooltip>
		</button>
	);
}

/** The explicit choice, for Settings where preferences belong. */
export function ThemeChoice() {
	const { preference, setPreference } = useTheme();

	return (
		<div className="inline-flex rounded-control border border-border bg-surface p-1">
			{ORDER.map((option) => {
				const Icon = ICON[option];
				const selected = option === preference;
				return (
					<button
						key={option}
						type="button"
						onClick={() => setPreference(option)}
						aria-pressed={selected}
						className={`flex items-center gap-2 rounded-[4px] px-3 py-1.5 text-[13px] transition-colors ${
							selected ? "bg-accent text-accent-fg" : "text-fg-muted hover:text-fg"
						}`}
					>
						<Icon size={14} strokeWidth={1.75} />
						{LABEL[option]}
					</button>
				);
			})}
		</div>
	);
}

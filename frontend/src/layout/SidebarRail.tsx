import { GitBranch, House, LogOut, Settings } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { motion } from "motion/react";
import { NavLink } from "react-router";
import { useSignOut } from "../auth/session";
import { RAIL_ACTIVE, RAIL_BUTTON, RAIL_IDLE, RailTooltip } from "../components/rail";
import { SiftMark } from "../components/SiftMark";
import { ThemeCycleButton } from "../theme/ThemeControls";

type RailItem = {
	readonly to: string;
	readonly label: string;
	readonly icon: LucideIcon;
};

const SECTIONS: readonly RailItem[] = [
	{ to: "/", label: "Home", icon: House },
	{ to: "/gitlab", label: "GitLab", icon: GitBranch },
];

const SETTINGS: RailItem = { to: "/settings", label: "Settings", icon: Settings };

function RailLink({ to, label, icon: Icon }: RailItem) {
	return (
		<NavLink
			to={to}
			end
			aria-label={label}
			className={({ isActive }) => `${RAIL_BUTTON} ${isActive ? RAIL_ACTIVE : RAIL_IDLE}`}
		>
			{({ isActive }) => (
				<>
					{isActive && (
						// one shared layoutId, so the marker slides between sections instead of blinking
						<motion.span
							layoutId="rail-active"
							className="absolute left-0 h-5 w-0.5 rounded-r-full bg-accent"
							transition={{ type: "spring", stiffness: 520, damping: 42 }}
						/>
					)}
					<Icon size={17} strokeWidth={1.75} />
					<RailTooltip>{label}</RailTooltip>
				</>
			)}
		</NavLink>
	);
}

export function SidebarRail() {
	const signOut = useSignOut();

	return (
		<nav
			aria-label="Sections"
			className="sticky top-0 flex h-dvh w-14 flex-none flex-col items-center gap-1 border-r border-border bg-surface py-4"
		>
			<span className="mb-3 flex size-10 items-center justify-center text-accent">
				<SiftMark className="size-4.5" label="Sift" />
			</span>

			{SECTIONS.map((section) => (
				<RailLink key={section.to} {...section} />
			))}

			<div className="mt-auto flex flex-col items-center gap-1">
				<ThemeCycleButton />
				<RailLink {...SETTINGS} />
				<button
					type="button"
					onClick={() => signOut.mutate()}
					disabled={signOut.isPending}
					aria-label="Sign out"
					className={`${RAIL_BUTTON} ${RAIL_IDLE}`}
				>
					<LogOut size={17} strokeWidth={1.75} />
					<RailTooltip>Sign out</RailTooltip>
				</button>
			</div>
		</nav>
	);
}

import { House, LogOut, Settings } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { motion } from "motion/react";
import { NavLink } from "react-router";
import { useSignOut } from "../auth/session";
import { RAIL_ACTIVE, RAIL_BUTTON, RAIL_IDLE, RailBadge, RailTooltip, unreadSuffix } from "../components/rail";
import { SiftMark } from "../components/SiftMark";
import { summaryFor, useFeedSummary } from "../feed/feed";
import { sourceIcon, sourceName, sourcePath } from "../sources/labels";
import { useSources } from "../sources/sources";
import { ThemeCycleButton } from "../theme/ThemeControls";

type RailItem = {
	readonly to: string;
	readonly label: string;
	readonly icon: LucideIcon;
	readonly unread?: number;
};

const HOME: RailItem = { to: "/", label: "Home", icon: House };
const SETTINGS: RailItem = { to: "/settings", label: "Settings", icon: Settings };

function RailLink({ to, label, icon: Icon, unread = 0 }: RailItem) {
	return (
		<NavLink
			to={to}
			end
			aria-label={`${label}${unreadSuffix(unread)}`}
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
					<RailBadge count={unread} />
					<RailTooltip>{label}</RailTooltip>
				</>
			)}
		</NavLink>
	);
}

export function SidebarRail() {
	const signOut = useSignOut();
	/*
	 * a source appears in the rail once it is connected and not before. an icon that leads to a page
	 * saying "not connected" is a dead end, and the offer to connect belongs on Home where it can say
	 * what the source is for.
	 */
	const { data: sources } = useSources();
	/*
	 * a count per source, which is what tells them apart at a glance once there is more than one of
	 * them. the same summary the tab badge and the Home cards read, so no two of them can disagree.
	 */
	const { data: summary } = useFeedSummary();

	return (
		<nav
			aria-label="Sections"
			className="sticky top-0 flex h-dvh w-14 flex-none flex-col items-center gap-1 border-r border-border bg-surface py-4"
		>
			<span className="mb-3 flex size-10 items-center justify-center text-accent">
				<SiftMark className="size-4.5" label="Sift" />
			</span>

			<RailLink {...HOME} />

			{(sources ?? []).map((source) => (
				<RailLink
					key={source.source}
					to={sourcePath(source.source)}
					label={sourceName(source.source)}
					icon={sourceIcon(source.source)}
					unread={summaryFor(summary, source.source).unread}
				/>
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

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
						/*
						 * one shared layoutId, so the marker slides between sections instead of blinking.
						 * it runs along the edge the rail is attached to, which is the top of the icon
						 * while the rail is a bar at the bottom of the screen.
						 */
						<motion.span
							layoutId="rail-active"
							className="absolute inset-x-1.5 top-0 h-0.5 rounded-b-full bg-accent sm:inset-x-auto sm:top-auto sm:left-0 sm:h-5 sm:w-0.5 sm:rounded-b-none sm:rounded-r-full"
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

	/*
	 * a column beside the content on a wide screen, and a bar along the bottom of a narrow one, where
	 * the reach of a thumb is what decides where navigation goes.
	 */
	return (
		<nav
			aria-label="Sections"
			className="fixed inset-x-0 bottom-0 z-20 flex h-14 w-full flex-none flex-row items-center justify-around gap-1 border-t border-border bg-surface px-2 sm:sticky sm:inset-x-auto sm:top-0 sm:bottom-auto sm:h-dvh sm:w-14 sm:flex-col sm:justify-start sm:border-t-0 sm:border-r sm:px-0 sm:py-4"
		>
			{/* the wordmark is the one thing worth the room it takes only when there is room */}
			<span className="hidden size-10 items-center justify-center text-accent sm:mb-3 sm:flex">
				<SiftMark className="size-4.5" label="Sift" />
			</span>

			{/*
			  * where to go, kept together as one cluster so the bar reads as two groups rather than as
			  * five loose icons. `contents` because the column below wants these as its own children,
			  * so that the controls can still take the space left over.
			  */}
			<div className="flex items-center gap-1 sm:contents">
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
			</div>

			<div className="flex flex-row items-center gap-1 sm:mt-auto sm:flex-col">
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

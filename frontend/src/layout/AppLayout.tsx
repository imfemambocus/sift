import { Outlet } from "react-router";
import { SidebarRail } from "./SidebarRail";

export function AppLayout() {
	return (
		<div className="flex min-h-dvh">
			<SidebarRail />
			<div className="min-w-0 flex-1">
				<Outlet />
			</div>
		</div>
	);
}

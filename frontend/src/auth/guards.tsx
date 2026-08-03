import { Navigate, Outlet } from "react-router";
import { Splash } from "../components/Splash";
import { useSession } from "./session";

export function RequireAuth() {
	const { data: session, isPending } = useSession();

	if (isPending) {
		return <Splash />;
	}
	if (session === null || session === undefined) {
		return <Navigate to="/sign-in" replace />;
	}
	return <Outlet />;
}

export function GuestOnly() {
	const { data: session, isPending } = useSession();

	if (isPending) {
		return <Splash />;
	}
	if (session !== null && session !== undefined) {
		return <Navigate to="/" replace />;
	}
	return <Outlet />;
}

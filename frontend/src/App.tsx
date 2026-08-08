import { MotionConfig } from "motion/react";
import { Navigate, Route, Routes } from "react-router";
import { CreateAccountPage } from "./auth/CreateAccountPage";
import { GuestOnly, RequireAuth } from "./auth/guards";
import { SignInPage } from "./auth/SignInPage";
import { WindowScrollbar } from "./components/WindowScrollbar";
import { AppLayout } from "./layout/AppLayout";
import { GitLabPage } from "./pages/GitLabPage";
import { GmailPage } from "./pages/GmailPage";
import { HomePage } from "./pages/HomePage";
import { SettingsPage } from "./pages/SettingsPage";

export function App() {
	return (
		// honours the os reduced-motion setting for every animation in the app
		<MotionConfig reducedMotion="user">
			<WindowScrollbar />
			<Routes>
				<Route element={<GuestOnly />}>
					<Route path="/sign-in" element={<SignInPage />} />
					<Route path="/create-account" element={<CreateAccountPage />} />
				</Route>

				<Route element={<RequireAuth />}>
					<Route element={<AppLayout />}>
						<Route index element={<HomePage />} />
						<Route path="gitlab" element={<GitLabPage />} />
						<Route path="gmail" element={<GmailPage />} />
						<Route path="settings" element={<SettingsPage />} />
					</Route>
				</Route>

				<Route path="*" element={<Navigate to="/" replace />} />
			</Routes>
		</MotionConfig>
	);
}

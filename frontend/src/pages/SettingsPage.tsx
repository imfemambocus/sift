import { useSession, useSignOut } from "../auth/session";
import { Button } from "../components/Button";
import { Page } from "../layout/Page";
import { ConnectGitLab } from "../sources/ConnectGitLab";
import { ThemeChoice } from "../theme/ThemeControls";

export function SettingsPage() {
	const { data: session } = useSession();
	const signOut = useSignOut();

	if (session === null || session === undefined) {
		return null;
	}

	return (
		<Page title="Settings">
			<ConnectGitLab />

			<section className="flex flex-col items-start gap-3">
				<h2 className="eyebrow">Account</h2>
				<dl className="flex w-full flex-col gap-2.5 rounded-panel border border-border bg-surface px-4 py-3.5">
					<div className="flex items-baseline justify-between gap-4">
						<dt className="text-[13px] text-fg-muted">Name</dt>
						<dd className="text-[13px] text-fg">{session.displayName}</dd>
					</div>
					<div className="flex items-baseline justify-between gap-4">
						<dt className="text-[13px] text-fg-muted">Email</dt>
						<dd className="font-mono text-[12px] text-fg">{session.email}</dd>
					</div>
				</dl>
				<Button variant="ghost" onClick={() => signOut.mutate()} disabled={signOut.isPending}>
					Sign out
				</Button>
			</section>

			<section className="flex flex-col items-start gap-3">
				<h2 className="eyebrow">Appearance</h2>
				<ThemeChoice />
			</section>
		</Page>
	);
}

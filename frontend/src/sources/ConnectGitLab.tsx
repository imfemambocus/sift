import { Unplug } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "../components/Button";
import { FormError } from "../components/FormError";
import { Spinner } from "../components/Spinner";
import { errorMessage } from "../lib/api";
import { CheckNowButton } from "./CheckNowButton";
import { agoPhrase } from "../lib/time";
import type { SourceStatus } from "./sources";
import { useDisconnectSource, useGitLabOAuth, useSource, useStartGitLabOAuth } from "./sources";

const SOURCE = "gitlab";

type Tone = "ok" | "warn" | "error";

const TONE_CLASS: Record<Tone, string> = {
	ok: "text-fg",
	warn: "text-fg-muted",
	error: "text-danger",
};

/*
 * what the callback can say back. it redirects with a fixed word rather than a message, so nothing
 * a remote server wrote is carried in the URL of this app.
 */
const CALLBACK_MESSAGE: Record<string, string> = {
	denied: "GitLab was not connected. The approval was refused or it did not come back.",
	failed: "GitLab would not complete the connection. Check the application on your instance, then try again.",
};

function statusLine(source: SourceStatus): { tone: Tone; text: string } {
	if (source.status === "AUTH_FAILED") {
		return { tone: "error", text: "GitLab rejected the connection. Connect it again below." };
	}
	if (source.status === "ERROR") {
		return { tone: "warn", text: "The last read failed. Sift will try again on its own." };
	}
	if (source.status === "NEVER_RUN") {
		return { tone: "warn", text: "Connected. Waiting for the first read." };
	}
	if (source.lastSyncAt === null) {
		return { tone: "ok", text: "Connected." };
	}
	// "synced" rather than "read": items are read or unread now, and one word cannot mean both
	return { tone: "ok", text: `Connected. Last synced ${agoPhrase(source.lastSyncAt)}.` };
}

type SourceCardProps = {
	readonly source: SourceStatus;
	readonly canReconnect: boolean;
	readonly onReconnect: () => void;
	readonly onDisconnect: () => void;
	readonly disconnecting: boolean;
};

function SourceCard({ source, canReconnect, onReconnect, onDisconnect, disconnecting }: SourceCardProps) {
	const [confirming, setConfirming] = useState(false);
	const status = statusLine(source);

	return (
		<div className="flex w-full flex-col gap-3 rounded-panel border border-border bg-surface px-4 py-3.5">
			<p className="font-mono text-[12px] text-fg">{source.instanceUrl}</p>

			<p className={`text-[13px] ${TONE_CLASS[status.tone]}`}>{status.text}</p>

			{source.lastError !== null && (
				<p className="font-mono text-[11px] leading-relaxed text-fg-muted">{source.lastError}</p>
			)}

			<p className="text-[13px] text-fg-muted">
				{source.itemCount} {source.itemCount === 1 ? "item" : "items"} in your feed.
			</p>

			{/*
			  * three different weights on purpose: checking is the safe one, authorizing again is a
			  * detour, and disconnecting throws this source's items away
			  */}
			<div className="flex flex-wrap items-center gap-2">
				<CheckNowButton source={source.source} />
				{canReconnect && (
					<Button variant="subtle" onClick={onReconnect}>
						Connect again
					</Button>
				)}
				{confirming ? (
					<>
						<Button variant="dangerSolid" onClick={onDisconnect} disabled={disconnecting}>
							{disconnecting && <Spinner />}
							{disconnecting ? "Disconnecting" : "Yes, disconnect"}
						</Button>
						<Button variant="subtle" onClick={() => setConfirming(false)} disabled={disconnecting}>
							Keep it
						</Button>
					</>
				) : (
					<Button variant="danger" onClick={() => setConfirming(true)}>
						<Unplug size={14} strokeWidth={1.75} aria-hidden />
						Disconnect
					</Button>
				)}
			</div>
		</div>
	);
}

/*
 * an instance with no OAuth application has no way in at all, since the pasted-token path was
 * removed on 2026-08-07. so this says what to do rather than showing a button that cannot work.
 */
function NotConfigured() {
	return (
		<div className="flex w-full max-w-sm flex-col gap-2 rounded-panel border border-border bg-surface px-4 py-3.5">
			<p className="text-[13px] leading-relaxed text-fg-muted">
				This Sift has no GitLab application yet, so it cannot ask GitLab for permission.
			</p>
			<p className="text-[13px] leading-relaxed text-fg-muted">
				Make one on your instance under User Settings, Applications, with the{" "}
				<span className="font-mono text-[12px] text-fg">read_api</span> scope. Then set the four{" "}
				<span className="font-mono text-[12px] text-fg">SIFT_GITLAB_</span> values in{" "}
				<span className="font-mono text-[12px] text-fg">.env</span> and start Sift again.
			</p>
		</div>
	);
}

export function ConnectGitLab() {
	const { data: source } = useSource(SOURCE);
	const { data: oauth } = useGitLabOAuth();
	const disconnect = useDisconnectSource(SOURCE);
	const startOAuth = useStartGitLabOAuth();
	const [searchParams] = useSearchParams();

	const [reconnecting, setReconnecting] = useState(false);

	// a rejected connection opens the button on its own: there is nothing else the user can do
	const needsCredential = source === undefined || reconnecting || source.status === "AUTH_FAILED";
	const callbackMessage = CALLBACK_MESSAGE[searchParams.get("gitlab") ?? ""] ?? null;
	const message = errorMessage(disconnect.error) ?? errorMessage(startOAuth.error) ?? callbackMessage;

	if (oauth === undefined) {
		return null;
	}

	return (
		<section className="flex w-full flex-col items-start gap-3">
			<h2 className="eyebrow">GitLab</h2>

			{source !== undefined && (
				<SourceCard
					source={source}
					canReconnect={!needsCredential}
					onReconnect={() => setReconnecting(true)}
					onDisconnect={() => disconnect.mutate()}
					disconnecting={disconnect.isPending}
				/>
			)}

			{needsCredential && !oauth.configured && <NotConfigured />}

			{needsCredential && oauth.configured && (
				<div className="flex w-full max-w-sm flex-col gap-4">
					<p className="text-[13px] leading-relaxed text-fg-muted">
						Sift reads your GitLab to-do list, which is already only about you. You approve it once on{" "}
						<span className="font-mono text-[12px] text-fg">{oauth.instanceUrl}</span>, with the{" "}
						<span className="font-mono text-[12px] text-fg">read_api</span> scope, which cannot change
						anything. You can withdraw it there at any time.
					</p>

					{message !== null && <FormError>{message}</FormError>}

					<div className="flex flex-wrap items-center gap-2">
						<Button onClick={() => startOAuth.mutate()} disabled={startOAuth.isPending}>
							{startOAuth.isPending && <Spinner />}
							{startOAuth.isPending ? "Opening GitLab" : "Connect with GitLab"}
						</Button>
						{reconnecting && (
							<Button variant="subtle" onClick={() => setReconnecting(false)}>
								Cancel
							</Button>
						)}
					</div>
				</div>
			)}
		</section>
	);
}

import { Unplug } from "lucide-react";
import type { ReactNode } from "react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "../components/Button";
import { FormError } from "../components/FormError";
import { Spinner } from "../components/Spinner";
import { errorMessage } from "../lib/api";
import { agoPhrase } from "../lib/time";
import { CheckNowButton } from "./CheckNowButton";
import { sourceName } from "./labels";
import type { SourceStatus } from "./sources";
import { useConnector, useDisconnectSource, useSource, useStartOAuth } from "./sources";

type Tone = "ok" | "warn" | "error";

const TONE_CLASS: Record<Tone, string> = {
	ok: "text-fg",
	warn: "text-fg-muted",
	error: "text-danger",
};

/*
 * what a callback can say back. it redirects with a fixed word rather than a message, so nothing a
 * remote server wrote is carried in the URL of this app.
 */
const CALLBACK_MESSAGE: Record<string, string> = {
	denied: "It was not connected. The approval was refused or it did not come back.",
	failed: "The connection could not be completed. Check the application, then try again.",
};

function statusLine(source: SourceStatus): { tone: Tone; text: string } {
	const name = sourceName(source.source);
	if (source.status === "AUTH_FAILED") {
		return { tone: "error", text: `${name} rejected the connection. Connect it again below.` };
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
	// "synced" rather than "read": an item is read or unread, and one word cannot mean both
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
			{/* the account is what a person recognises, so it leads and the host explains it */}
			<p className="font-mono text-[12px] text-fg">{source.account ?? source.instanceUrl}</p>

			{source.account !== null && (
				<p className="font-mono text-[11px] text-fg-muted">{source.instanceUrl}</p>
			)}

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

const MONO = "font-mono text-[12px] text-fg";

/*
 * a source with no application registered has no way in at all, since an approval is the only one.
 * so this says what to do rather than showing a button that cannot work.
 */
function setupInstructions(source: string, target: string | null): ReactNode {
	if (source === "gmail") {
		return (
			<>
				<p className="text-[13px] leading-relaxed text-fg-muted">
					This Sift has no Google client yet, so it cannot ask Google for permission.
				</p>
				<p className="text-[13px] leading-relaxed text-fg-muted">
					Make one in a Google Cloud project: enable the Gmail API, add an OAuth client of type Web
					application, and give it the redirect URI{" "}
					<span className={MONO}>http://localhost:7777/api/sources/gmail/oauth/callback</span>. Then set the
					three <span className={MONO}>SIFT_GMAIL_</span> values in <span className={MONO}>.env</span> and
					start Sift again.
				</p>
			</>
		);
	}
	return (
		<>
			<p className="text-[13px] leading-relaxed text-fg-muted">
				This Sift has no {sourceName(source)} application yet, so it cannot ask for permission.
			</p>
			<p className="text-[13px] leading-relaxed text-fg-muted">
				Make one on your instance under User Settings, Applications, with the{" "}
				<span className={MONO}>read_api</span> scope. Then set the four{" "}
				<span className={MONO}>SIFT_GITLAB_</span> values in <span className={MONO}>.env</span> and start Sift
				again. {target !== null && <>It must point at {target}.</>}
			</p>
		</>
	);
}

/** What the approval will let Sift see, in the person's own terms rather than in scope names. */
function approvalNote(source: string, target: string | null): ReactNode {
	if (source === "gmail") {
		return (
			<>
				Sift reads your messages so they sit in the same list as everything else, and so this app's search
				can find them. You approve it once on Google, with the{" "}
				<span className={MONO}>gmail.readonly</span> scope, which cannot send, label or delete anything. You
				can withdraw it in your Google account at any time.
			</>
		);
	}
	return (
		<>
			Sift reads your GitLab to-do list, which is already only about you. You approve it once on{" "}
			<span className={MONO}>{target}</span>, with the <span className={MONO}>read_api</span> scope, which
			cannot change anything. You can withdraw it there at any time.
		</>
	);
}

/**
 * One source's whole settings section: the card when it is connected, and the offer when it is not.
 *
 * <p>Only two paragraphs of prose differ per source, which is exactly what should differ: the
 * buttons, the statuses and the disconnect confirmation are one copy, so they cannot drift apart.
 */
export function ConnectSource({ source: slug }: { readonly source: string }) {
	const { data: source } = useSource(slug);
	const { data: connector } = useConnector(slug);
	const disconnect = useDisconnectSource(slug);
	const startOAuth = useStartOAuth(slug);
	const [searchParams] = useSearchParams();

	const [reconnecting, setReconnecting] = useState(false);

	// a rejected connection opens the button on its own: there is nothing else the user can do
	const needsCredential = source === undefined || reconnecting || source.status === "AUTH_FAILED";
	const callbackMessage = CALLBACK_MESSAGE[searchParams.get(slug) ?? ""] ?? null;
	const message = errorMessage(disconnect.error) ?? errorMessage(startOAuth.error) ?? callbackMessage;

	if (connector === undefined) {
		return null;
	}

	return (
		<section className="flex w-full flex-col items-start gap-3">
			<h2 className="eyebrow">{sourceName(slug)}</h2>

			{source !== undefined && (
				<SourceCard
					source={source}
					canReconnect={!needsCredential}
					onReconnect={() => setReconnecting(true)}
					onDisconnect={() => disconnect.mutate()}
					disconnecting={disconnect.isPending}
				/>
			)}

			{needsCredential && !connector.configured && (
				<div className="flex w-full max-w-sm flex-col gap-2 rounded-panel border border-border bg-surface px-4 py-3.5">
					{setupInstructions(slug, connector.target)}
				</div>
			)}

			{needsCredential && connector.configured && (
				<div className="flex w-full max-w-sm flex-col gap-4">
					<p className="text-[13px] leading-relaxed text-fg-muted">
						{approvalNote(slug, connector.target)}
					</p>

					{message !== null && <FormError>{message}</FormError>}

					<div className="flex flex-wrap items-center gap-2">
						<Button onClick={() => startOAuth.mutate()} disabled={startOAuth.isPending}>
							{startOAuth.isPending && <Spinner />}
							{startOAuth.isPending ? `Opening ${sourceName(slug)}` : `Connect with ${sourceName(slug)}`}
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
